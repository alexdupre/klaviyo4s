package com.alexdupre.klaviyo.core.internal

import com.alexdupre.klaviyo.core.{KlaviyoAuth, KlaviyoError, RetryPolicy}
import com.alexdupre.klaviyo.core.jsonapi.ErrorDocument
import com.alexdupre.klaviyo.core.KlaviyoClient
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString as jsonRead, JsonReaderException}
import sttp.client4.{Request, Response, SttpClientException}
import sttp.monad.MonadError
import sttp.monad.syntax.*

import scala.concurrent.duration.*
import scala.util.Random
import scala.util.control.NonFatal

/** Core request runner shared by every generated category method.
  *
  * The executor's responsibilities:
  *
  *   1. Inject the standard Klaviyo headers (`Authorization`,
  *      `revision`, `Accept`, `Content-Type`, `User-Agent`).
  *   2. Send the request via the user's chosen sttp backend.
  *   3. Retry on `HTTP 429` (honouring `Retry-After`) and `HTTP 503`
  *      (full-jitter exponential backoff) up to
  *      [[com.alexdupre.klaviyo.core.RetryPolicy.maxAttempts]].
  *   4. Map non-success responses to a [[KlaviyoError]] subtype and
  *      raise it through the effect type's natural error channel — no
  *      `Either` ever appears in user-facing signatures.
  *   5. Apply a caller-supplied decoder to 2xx bodies; decode failures
  *      become `KlaviyoError.Decode` so the offending payload is kept
  *      verbatim for diagnostics.
  *
  * Generated methods invoke [[Executor.execute]] exactly once per
  * endpoint and never construct retry logic themselves — keeping the
  * surface area of the code generator small.
  */
object Executor {

  /** Run a prepared request and decode the 2xx body into `A`.
    *
    * The request must be configured to return the raw response body as
    * `String` (via `asStringAlways`). We do not let sttp decode bodies
    * directly because we need the raw text for both error parsing and
    * for `KlaviyoError.Decode.rawBody` on failure.
    *
    * @param client  the [[KlaviyoClient]] whose backend, config and
    *                `Sleep[F]` drive the request
    * @param request the sttp request to send; auth + revision headers
    *                will be added on top of whatever is already set so
    *                callers may override either per-request
    * @param decode  function applied to the 2xx body to produce the
    *                final result. Typically a one-liner calling
    *                jsoniter's `readFromString[T]`.
    */
  def execute[F[_], A](
    client: KlaviyoClient[F],
    request: Request[String]
  )(decode: String => A): F[A] = {
    given MonadError[F] = client.backend.monad
    val prepared = withStandardHeaders(client, request)
    attempt(client, prepared, decode, attemptNumber = 1)
  }

  /** Single recursive step. Each call performs at most one HTTP request
    * and, on a retriable failure, schedules the next attempt via the
    * `Sleep[F]` instance held by the client.
    */
  private def attempt[F[_], A](
    client: KlaviyoClient[F],
    request: Request[String],
    decode: String => A,
    attemptNumber: Int
  )(using me: MonadError[F]): F[A] = {
    val policy = client.config.retry
    val idempotent = isIdempotent(request)
    me.handleError {
      client.backend.send(request).flatMap { resp =>
        classify(resp, policy, idempotent) match {
          case Outcome.Success(body) => decodeSuccess(body, decode)
          case Outcome.Retry(reason) if attemptNumber < policy.maxAttempts =>
            nextDelay(policy, reason, attemptNumber) match {
              case Some(delay) =>
                client.sleep.sleep(delay).flatMap(_ =>
                  attempt(client, request, decode, attemptNumber + 1)
                )
              case None =>
                // The server asked us to wait longer than `maxDelay`
                // (only possible for a `Retry-After`-bearing 429).
                // Retrying inside that window would almost certainly
                // hit the same rate limit again and burn an attempt
                // for nothing — surface the rate-limit error with
                // the SERVER's `retryAfter` so the caller can decide
                // to wait, schedule, or give up.
                me.error(toError(resp, rateLimited = true))
            }
          case Outcome.Retry(reason) =>
            // Out of attempts. The `reason` decides whether to surface
            // a rate-limit-flavoured error or a generic API error.
            me.error(toError(resp, rateLimited = reason.isInstanceOf[Outcome.RetryReason.RateLimited]))
          case Outcome.Fail => me.error(toError(resp, rateLimited = false))
        }
      }
    } {
      // Transport-layer exception (connection refused, timeout, TLS
      // error, ...). Whether we retry depends on:
      //   1. Has the user enabled `retryTransient`?
      //   2. Is the request idempotent (GET/HEAD/OPTIONS/TRACE/PUT/DELETE)?
      //   3. Is the exception "pre-send" (request never reached the
      //      server — always safe to retry) or "post-send / unknown"
      //      (only safe for idempotent methods)?
      //
      // sttp wraps backend exceptions in `SttpClientException`; unwrap
      // so user code sees the original `IOException` /
      // `ConnectException` etc. as the cause rather than sttp's
      // framing wrapper.
      case e: KlaviyoError => me.error(e)
      case e: SttpClientException =>
        val cause = Option(e.getCause).getOrElse(e)
        maybeRetryTransport(client, request, decode, attemptNumber, cause, idempotent)
      case NonFatal(cause) =>
        maybeRetryTransport(client, request, decode, attemptNumber, cause, idempotent)
    }
  }

  /** Decide whether a transport-layer exception is retriable and
    * either schedule the next attempt or surface it as
    * `KlaviyoError.Transport`. See [[isPreSendTransport]] for the
    * pre/post-send taxonomy.
    */
  private def maybeRetryTransport[F[_], A](
    client: KlaviyoClient[F],
    request: Request[String],
    decode: String => A,
    attemptNumber: Int,
    cause: Throwable,
    idempotent: Boolean
  )(using me: MonadError[F]): F[A] = {
    val policy = client.config.retry
    val preSend = isPreSendTransport(cause)
    val canRetry = policy.retryTransient && attemptNumber < policy.maxAttempts && (preSend || idempotent)
    if (canRetry) {
      // `Transient` always uses computed exp-backoff, which is
      // capped — `nextDelay` never returns `None` for this branch.
      val delay = nextDelay(policy, Outcome.RetryReason.Transient, attemptNumber).getOrElse(policy.maxDelay)
      client.sleep.sleep(delay).flatMap(_ =>
        attempt(client, request, decode, attemptNumber + 1)
      )
    } else {
      me.error(new KlaviyoError.Transport(cause))
    }
  }

  /** RFC 7231 idempotent methods. Replaying these is guaranteed by
    * the spec to produce the same end state, so we can safely retry
    * the post-send transport failure and 5xx server errors that don't
    * tell us whether the server processed the request.
    *
    * `POST` and `PATCH` are excluded because Klaviyo's API doesn't
    * guarantee server-side idempotency for them, so a retry could
    * create a duplicate resource or apply a change twice.
    */
  private def isIdempotent(request: Request[String]): Boolean =
    request.method.method.toUpperCase match {
      case "GET" | "HEAD" | "OPTIONS" | "TRACE" | "PUT" | "DELETE" => true
      case _ => false
    }

  /** Pre-send transport failures: the request bytes never reached the
    * server, so retrying is safe for any HTTP method. Post-send /
    * indeterminate failures (read-timeouts, mid-stream I/O errors)
    * fall through to the idempotency check.
    *
    * We can't tell from a `SocketTimeoutException` whether it was a
    * connect-timeout (pre-send) or read-timeout (post-send), so it's
    * conservatively classified as post-send.
    */
  private def isPreSendTransport(cause: Throwable): Boolean = cause match {
    case _: java.net.ConnectException => true
    case _: java.net.UnknownHostException => true
    case _: java.net.NoRouteToHostException => true
    case _: javax.net.ssl.SSLHandshakeException => true
    case _ => false
  }

  /** Apply the user-supplied decoder to a 2xx body, wrapping any
    * decoder exception into a [[KlaviyoError.Decode]] that preserves the
    * raw response text.
    */
  private def decodeSuccess[F[_], A](
    body: String,
    decode: String => A
  )(using me: MonadError[F]): F[A] = {
    try {
      me.unit(decode(body))
    } catch {
      case NonFatal(e) => me.error(new KlaviyoError.Decode(e, body))
    }
  }

  /** Compute the delay before the next attempt.
    *
    * Returns `None` to mean "don't retry — fail-fast" and `Some(d)`
    * to mean "sleep for `d` then retry". The only path that produces
    * `None` is a `Retry-After`-bearing 429 whose value exceeds the
    * policy's `maxDelay`: the server is authoritative on rate-limit
    * windows, so retrying earlier would almost certainly hit the
    * same limit again and burn an attempt. Surface the rate-limit
    * error instead and let the caller decide.
    *
    * For every other retriable reason (`HTTP 503`, transient 5xx,
    * transport exceptions) the delay is computed via exponential
    * backoff (`base * 2^(attempt-1)`), capped at `maxDelay`, then
    * passed through the configured jitter strategy. `maxDelay`
    * legitimately bounds those because our exp-backoff number is a
    * heuristic — capping it is the entire point of the cap.
    */
  private def nextDelay(
    policy: RetryPolicy,
    reason: Outcome.RetryReason,
    attemptNumber: Int
  ): Option[FiniteDuration] = reason match {
    case Outcome.RetryReason.RateLimited(Some(after)) =>
      val raw = FiniteDuration(after, SECONDS)
      // Server-supplied wait — honour it verbatim if it fits the
      // policy. No jitter (the server's number is authoritative;
      // randomising it just means we wake up early and get rate-
      // limited again). If it exceeds `maxDelay`, fail-fast.
      if (raw > policy.maxDelay) None else Some(raw)
    case _ =>
      // exp backoff: base * 2^(attempt-1)
      val factor = math.pow(2.0d, (attemptNumber - 1).toDouble)
      val raw = FiniteDuration((policy.baseDelay.toMillis * factor).toLong, MILLISECONDS)
      val capped = if (raw > policy.maxDelay) policy.maxDelay else raw
      val jittered = policy.jitter match {
        case RetryPolicy.Jitter.None => capped
        case RetryPolicy.Jitter.Full =>
          // Draw uniformly from [0, capped]. Avoids the
          // thundering-herd problem when many clients hit the same
          // limit simultaneously.
          FiniteDuration((Random.nextDouble() * capped.toMillis).toLong, MILLISECONDS)
      }
      Some(jittered)
  }

  /** Convert a non-success response into a [[KlaviyoError]]. */
  private def toError(resp: Response[String], rateLimited: Boolean): KlaviyoError = {
    val errors = parseErrors(resp.body)
    if (rateLimited) {
      val retryAfter = parseRetryAfter(resp).getOrElse(0.seconds)
      new KlaviyoError.RateLimited(retryAfter, errors, resp.body)
    } else {
      new KlaviyoError.Api(resp.code.code, errors, resp.body)
    }
  }

  /** Best-effort error-body decoding. We never let a malformed body mask
    * the underlying HTTP failure: if jsoniter throws, return an empty
    * error list — the caller still gets the raw body and status code.
    */
  private def parseErrors(body: String): List[com.alexdupre.klaviyo.core.jsonapi.ErrorObject] = {
    try {
      jsonRead[ErrorDocument](body).errors
    } catch {
      case _: JsonReaderException => Nil
      case NonFatal(_) => Nil
    }
  }

  /** Parse the `Retry-After` header. Klaviyo always uses the
    * delta-seconds form; the HTTP-date form (also valid per RFC 7231)
    * is not used by Klaviyo but is tolerated here for forward-compat.
    */
  private[internal] def parseRetryAfter(resp: Response[String]): Option[FiniteDuration] = {
    resp
      .header("Retry-After")
      .flatMap { raw =>
        raw.trim.toLongOption.map(s => FiniteDuration(s, SECONDS))
      }
  }

  /** Classify a response into one of three buckets used by the retry
    * loop. Kept as a small ADT so the retry/return path in [[attempt]]
    * stays simple and pattern-matchable.
    */
  private enum Outcome {
    case Success(body: String)
    case Retry(reason: Outcome.RetryReason)
    case Fail
  }

  private object Outcome {

    enum RetryReason {
      case RateLimited(retryAfter: Option[Int])
      case ServiceUnavailable

      /** Transient server failure (500 / 502 / 504) or post-send
        * transport exception — only emitted by [[classify]] for
        * idempotent methods when `retryTransient` is enabled.
        */
      case Transient
    }
  }

  private def classify(resp: Response[String], policy: RetryPolicy, idempotent: Boolean): Outcome = {
    val code = resp.code.code
    if (code >= 200 && code < 300) Outcome.Success(resp.body)
    else if (code == 429) {
      val ra = parseRetryAfter(resp).map(_.toSeconds.toInt)
      Outcome.Retry(Outcome.RetryReason.RateLimited(ra))
    } else if (code == 503) {
      Outcome.Retry(Outcome.RetryReason.ServiceUnavailable)
    } else if (policy.retryTransient && idempotent && (code == 500 || code == 502 || code == 504)) {
      // Transient server failures where the server's processing
      // state for the original request is unknown. Replaying is
      // only safe when the method is idempotent — we already
      // checked that.
      Outcome.Retry(Outcome.RetryReason.Transient)
    } else {
      Outcome.Fail
    }
  }

  /** Apply the Klaviyo-mandated request headers on top of whatever the
    * generated code already set, and pin the read timeout from the
    * config. Per-request overrides win when the user has set the
    * header explicitly (e.g. a one-off revision pin); the read
    * timeout is always set from the config because sttp's
    * `.readTimeout` is last-write-wins.
    */
  private def withStandardHeaders[F[_]](
    client: KlaviyoClient[F],
    request: Request[String]
  ): Request[String] = {
    val cfg = client.config
    val authHeader = cfg.auth match {
      case KlaviyoAuth.PrivateKey(value) => s"Klaviyo-API-Key $value"
    }
    val existingNames = request.headers.map(_.name.toLowerCase).toSet
    def addIfMissing(req: Request[String], name: String, value: String): Request[String] =
      if (existingNames.contains(name.toLowerCase)) req else req.header(name, value)
    val withAuth = addIfMissing(request, "Authorization", authHeader)
    val withRevision = addIfMissing(withAuth, "revision", cfg.revision)
    val withAccept = addIfMissing(withRevision, "Accept", "application/vnd.api+json")
    val withUserAgent = addIfMissing(withAccept, "User-Agent", cfg.userAgent)
    withUserAgent.readTimeout(cfg.readTimeout)
  }
}
