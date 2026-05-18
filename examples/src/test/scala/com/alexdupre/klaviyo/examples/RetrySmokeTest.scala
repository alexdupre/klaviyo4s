package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.core.RetryPolicy
import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.{Header, StatusCode}
import sttp.shared.Identity

import scala.concurrent.duration.*

/** End-to-end smoke test for the retry executor.
  *
  * Two flows are exercised:
  *
  *   - 429 with `Retry-After: 0` → executor honors the header, retries
  *     once, second attempt succeeds.
  *   - 503 → executor falls back to exponential backoff with full
  *     jitter, eventually succeeds within `maxAttempts`.
  *
  * We use a tight `RetryPolicy` (base 1ms, max 10ms, jitter Full) so
  * the test stays fast even with multiple attempts.
  */
final class RetrySmokeTest extends munit.FunSuite {

  private val fastRetry: RetryPolicy =
    RetryPolicy(maxAttempts = 4, baseDelay = 1.millis, maxDelay = 10.millis)

  test("429 with Retry-After:0 → executor retries and succeeds") {
    val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      val n = attempts.incrementAndGet()
      if (n == 1) ResponseStub.adjust(
        """{"errors":[{"id":"x","status":429,"code":"rate_limited","title":"Too many","detail":"slow down"}]}""",
        StatusCode.TooManyRequests,
        Seq(Header("Retry-After", "0"))
      )
      else ResponseStub.adjust(
        """{"data": [], "links": { "self": "x" }}""",
        StatusCode.Ok
      )
    }

    val client = new KlaviyoClient[Identity](
      stub,
      KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("k"), revision = GeneratedSpecRevision, retry = fastRetry)
    )

    val _ = client.accounts.getAccounts()
    assertEquals(attempts.get(), 2, "executor must retry once after Retry-After")
  }

  test("503 → executor exponentially backs off and succeeds") {
    val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      val n = attempts.incrementAndGet()
      if (n < 3) ResponseStub.adjust(
        """{"errors":[{"id":"x","status":503,"code":"unavailable","title":"upstream busy","detail":"retry"}]}""",
        StatusCode.ServiceUnavailable
      )
      else ResponseStub.adjust(
        """{"data": [], "links": { "self": "x" }}""",
        StatusCode.Ok
      )
    }

    val client = new KlaviyoClient[Identity](
      stub,
      KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("k"), revision = GeneratedSpecRevision, retry = fastRetry)
    )

    val _ = client.accounts.getAccounts()
    assertEquals(attempts.get(), 3, "executor must retry twice after 503 before success")
  }

  test("exhausting retries surfaces KlaviyoError.RateLimited") {
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      ResponseStub.adjust(
        """{"errors":[{"id":"x","status":429,"code":"rate_limited","title":"Too many","detail":"go away"}]}""",
        StatusCode.TooManyRequests,
        Seq(Header("Retry-After", "0"))
      )
    }

    val client = new KlaviyoClient[Identity](
      stub,
      KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("k"), revision = GeneratedSpecRevision, retry = fastRetry.copy(maxAttempts = 2))
    )

    val ex = intercept[KlaviyoError.RateLimited] {
      val _ = client.accounts.getAccounts()
    }
    assert(ex.errors.nonEmpty)
  }
}
