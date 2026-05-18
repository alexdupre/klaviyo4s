package com.alexdupre.klaviyo.core

import com.alexdupre.klaviyo.core.*
import com.alexdupre.klaviyo.core.internal.Executor
import sttp.client4.*
import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.{Header, StatusCode}
import sttp.shared.Identity

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** Exercises [[Executor]] against `SyncBackendStub` to verify the
  * retry policy, header injection, and error-channel discipline.
  *
  * The Identity backend is used throughout — it makes assertions about
  * thrown exceptions cleaner and the small `baseDelay` (1ms) keeps the
  * total test wall time negligible.
  */
final class ExecutorSpec extends munit.FunSuite {

  private val fastRetry = RetryPolicy(
    maxAttempts = 4,
    baseDelay = 1.millis,
    maxDelay = 5.millis,
    jitter = RetryPolicy.Jitter.None
  )

  private val cfg = KlaviyoConfig(
    auth = KlaviyoAuth.PrivateKey("secret"),
    revision = "2026-04-15",
    retry = fastRetry
  )

  private def mkClient(backend: SyncBackend): KlaviyoClient[Identity] =
    new KlaviyoClient[Identity](backend, cfg)

  private val anyRequest = basicRequest.get(uri"https://a.klaviyo.com/api/x").response(asStringAlways)

  test("2xx body is decoded and returned") {
    val stub = SyncBackendStub.whenAnyRequest.thenRespondAdjust("\"hello\"", StatusCode.Ok)
    val out  = Executor.execute(mkClient(stub), anyRequest)(b => b.trim.stripPrefix("\"").stripSuffix("\""))
    assertEquals(out, "hello")
  }

  test("standard headers are attached to the outgoing request") {
    var seen: Option[Map[String, String]] = None
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { req =>
      seen = Some(req.headers.map(h => h.name.toLowerCase -> h.value).toMap)
      ResponseStub.adjust("\"ok\"", StatusCode.Ok)
    }
    val _ = Executor.execute(mkClient(stub), anyRequest)(_ => ())
    val h = seen.getOrElse(fail("request never executed"))
    assertEquals(h.get("authorization"), Some("Klaviyo-API-Key secret"))
    assertEquals(h.get("revision"), Some(cfg.revision))
    assertEquals(h.get("accept"), Some("application/vnd.api+json"))
    assert(h.get("user-agent").exists(_.startsWith("klaviyo4s")))
  }

  test("readTimeout from KlaviyoConfig lands on the outgoing request") {
    var seenTimeout: Option[scala.concurrent.duration.Duration] = None
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { req =>
      seenTimeout = Some(req.options.readTimeout)
      ResponseStub.adjust("\"ok\"", StatusCode.Ok)
    }
    val customCfg = cfg.copy(readTimeout = 7.seconds)
    val client    = new KlaviyoClient[Identity](stub, customCfg)
    Executor.execute(client, anyRequest)(identity)
    assertEquals(seenTimeout, Some(7.seconds: scala.concurrent.duration.Duration))
  }

  test("per-request header overrides the default") {
    var seenRevision: Option[String] = None
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { req =>
      seenRevision = req.headers.find(_.name.equalsIgnoreCase("revision")).map(_.value)
      ResponseStub.adjust("\"ok\"", StatusCode.Ok)
    }
    val req = anyRequest.header(Header("revision", "2099-01-01"))
    val _   = Executor.execute(mkClient(stub), req)(_ => ())
    assertEquals(seenRevision, Some("2099-01-01"))
  }

  test("503 retries with exponential backoff and ultimately succeeds") {
    val attempts = AtomicInteger(0)
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      val n = attempts.incrementAndGet()
      if (n < 3) ResponseStub.adjust("", StatusCode.ServiceUnavailable)
      else ResponseStub.adjust("\"ok\"", StatusCode.Ok)
    }
    val out = Executor.execute(mkClient(stub), anyRequest)(b => b.trim.stripPrefix("\"").stripSuffix("\""))
    assertEquals(out, "ok")
    assertEquals(attempts.get(), 3)
  }

  test("429 with Retry-After waits and retries") {
    val attempts = AtomicInteger(0)
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      val n = attempts.incrementAndGet()
      if (n == 1)
        ResponseStub.adjust(
          "{\"errors\":[{\"status\":429}]}",
          StatusCode.TooManyRequests,
          headers = Seq(Header("Retry-After", "0"))
        )
      else ResponseStub.adjust("\"ok\"", StatusCode.Ok)
    }
    val out = Executor.execute(mkClient(stub), anyRequest)(b => b.trim.stripPrefix("\"").stripSuffix("\""))
    assertEquals(out, "ok")
    assertEquals(attempts.get(), 2)
  }

  test("after maxAttempts of 429, surfaces as KlaviyoError.RateLimited") {
    val stub = SyncBackendStub.whenAnyRequest.thenRespondAdjust(
      "{\"errors\":[{\"status\":429,\"detail\":\"rate limit hit\"}]}",
      StatusCode.TooManyRequests
    )
    val ex = intercept[KlaviyoError.RateLimited] {
      Executor.execute(mkClient(stub), anyRequest)(identity)
    }
    assertEquals(ex.status, 429)
    assertEquals(ex.errors.headOption.flatMap(_.detail.toOption), Some("rate limit hit"))
  }

  test("429 with Retry-After exceeding maxDelay fails fast — no sleep, no retry") {
    val attempts = new AtomicInteger(0)
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      attempts.incrementAndGet()
      ResponseStub.adjust(
        "{\"errors\":[{\"status\":429}]}",
        StatusCode.TooManyRequests,
        // `fastRetry.maxDelay` is 5ms; 60s is far beyond it. Retrying
        // before the server's window resets would just hit the same
        // 429 — we should fail-fast with the SERVER's retryAfter.
        headers = Seq(Header("Retry-After", "60"))
      )
    }
    val ex = intercept[KlaviyoError.RateLimited] {
      Executor.execute(mkClient(stub), anyRequest)(identity)
    }
    assertEquals(attempts.get(), 1, "request must NOT be retried when Retry-After > maxDelay")
    assertEquals(ex.retryAfter, 60.seconds, "exception carries the server's full Retry-After, not the capped value")
  }

  test("non-retriable 4xx surfaces as KlaviyoError.Api with parsed errors") {
    val stub = SyncBackendStub.whenAnyRequest.thenRespondAdjust(
      "{\"errors\":[{\"status\":400,\"code\":\"invalid_input\",\"detail\":\"bad filter\"}]}",
      StatusCode.BadRequest
    )
    val ex = intercept[KlaviyoError.Api] {
      Executor.execute(mkClient(stub), anyRequest)(identity)
    }
    assertEquals(ex.status, 400)
    assertEquals(ex.errors.headOption.flatMap(_.code.toOption), Some("invalid_input"))
  }

  test("malformed 2xx body produces KlaviyoError.Decode with raw body preserved") {
    val raw  = "not json"
    val stub = SyncBackendStub.whenAnyRequest.thenRespondAdjust(raw, StatusCode.Ok)
    val ex   = intercept[KlaviyoError.Decode] {
      Executor.execute(mkClient(stub), anyRequest)(_ => throw new RuntimeException("decoder failed"))
    }
    assertEquals(ex.rawBody, raw)
  }

  test("transport exception surfaces as KlaviyoError.Transport") {
    val cause = new java.net.ConnectException("connection refused")
    val stub  = SyncBackendStub.whenAnyRequest.thenRespondF { _ => throw cause }
    val ex    = intercept[KlaviyoError.Transport] {
      Executor.execute(mkClient(stub), anyRequest)(identity)
    }
    assertEquals(ex.getCause, cause)
  }

  // ------------------------------------------------------------------
  // Transient-error retries (new behaviour, gated on `retryTransient`).
  // ------------------------------------------------------------------

  test("GET retries on 502 and surfaces success after a transient failure") {
    val attempts = new AtomicInteger(0)
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      val n = attempts.incrementAndGet()
      if (n == 1) ResponseStub.adjust("", StatusCode.BadGateway)
      else ResponseStub.adjust("\"ok\"", StatusCode.Ok)
    }
    val out = Executor.execute(mkClient(stub), anyRequest)(_ => "ok")
    assertEquals(out, "ok")
    assertEquals(attempts.get(), 2)
  }

  test("POST does NOT retry on 502 — surfaces as KlaviyoError.Api(502)") {
    val attempts = new AtomicInteger(0)
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      attempts.incrementAndGet()
      ResponseStub.adjust("", StatusCode.BadGateway)
    }
    val postReq = basicRequest.post(uri"https://a.klaviyo.com/api/x").response(asStringAlways)
    val ex      = intercept[KlaviyoError.Api] {
      Executor.execute(mkClient(stub), postReq)(identity)
    }
    assertEquals(ex.status, 502)
    assertEquals(attempts.get(), 1, "POST/502 must not be retried")
  }

  test("POST retries on pre-send ConnectException — request never reached the server") {
    val attempts = new AtomicInteger(0)
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      val n = attempts.incrementAndGet()
      if (n < 3) throw new java.net.ConnectException("connection refused")
      else ResponseStub.adjust("\"ok\"", StatusCode.Ok)
    }
    val postReq = basicRequest.post(uri"https://a.klaviyo.com/api/x").response(asStringAlways)
    val out     = Executor.execute(mkClient(stub), postReq)(_ => "ok")
    assertEquals(out, "ok")
    assertEquals(attempts.get(), 3)
  }

  test("POST does NOT retry on post-send SocketTimeoutException") {
    val attempts = new AtomicInteger(0)
    val cause    = new java.net.SocketTimeoutException("read timed out")
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      attempts.incrementAndGet()
      throw cause
    }
    val postReq = basicRequest.post(uri"https://a.klaviyo.com/api/x").response(asStringAlways)
    val ex      = intercept[KlaviyoError.Transport] {
      Executor.execute(mkClient(stub), postReq)(identity)
    }
    assertEquals(ex.getCause, cause)
    assertEquals(attempts.get(), 1, "POST/socket-timeout must not be retried (post-send / unclear)")
  }

  test("GET retries on post-send SocketTimeoutException because the method is idempotent") {
    val attempts = new AtomicInteger(0)
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      val n = attempts.incrementAndGet()
      if (n < 3) throw new java.net.SocketTimeoutException("read timed out")
      else ResponseStub.adjust("\"ok\"", StatusCode.Ok)
    }
    val out = Executor.execute(mkClient(stub), anyRequest)(_ => "ok")
    assertEquals(out, "ok")
    assertEquals(attempts.get(), 3)
  }

  test("retryTransient = false collapses to legacy behaviour (no 5xx / no transport retries)") {
    val legacyCfg = cfg.copy(retry = fastRetry.copy(retryTransient = false))
    val attempts  = new AtomicInteger(0)
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      attempts.incrementAndGet()
      ResponseStub.adjust("", StatusCode.BadGateway)
    }
    val client = new KlaviyoClient[Identity](stub, legacyCfg)
    val ex     = intercept[KlaviyoError.Api](Executor.execute(client, anyRequest)(identity))
    assertEquals(ex.status, 502)
    assertEquals(attempts.get(), 1, "with retryTransient=false, GET/502 must not retry")
  }
}
