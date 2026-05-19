package com.alexdupre.klaviyo.core

import scala.concurrent.duration.*

/** Retry policy applied uniformly to every request by the executor.
  *
  * **Always retried** (regardless of HTTP method or [[retryTransient]]):
  *
  *   - **HTTP 429** — rate limit hit. The executor honours the
  *     `Retry-After` header value (in seconds), capped at [[maxDelay]].
  *   - **HTTP 503** — service unavailable. Full-jitter exponential
  *     backoff. Klaviyo guarantees a 503 means the request was
  *     rejected, not partially processed, so it's safe even for
  *     non-idempotent methods.
  *
  * **Retried only when [[retryTransient]] is `true` AND the HTTP method
  * is idempotent** (`GET`, `HEAD`, `OPTIONS`, `TRACE`, `PUT`, `DELETE`):
  *
  *   - **HTTP 500 / 502 / 504** — transient server failures where the
  *     server's processing state is unknown. Safe to retry only for
  *     idempotent methods.
  *   - **Post-send transport exceptions** — socket timeout, mid-stream
  *     I/O failure. The request bytes may have reached the server, so
  *     retrying a `POST` or `PATCH` risks duplicate work.
  *
  * **Retried when [[retryTransient]] is `true` regardless of method**:
  *
  *   - **Pre-send transport exceptions** — connect refused, DNS
  *     resolution failure, TLS handshake failure. The request never
  *     reached the server, so retrying is always safe.
  *
  * Other failures (other 4xx, decode errors) are never retried because
  * retrying cannot change their outcome.
  *
  * @param maxAttempts    maximum number of HTTP attempts including the
  *                       initial request. `maxAttempts = 1` disables
  *                       retries.
  * @param baseDelay      delay for the first retry, before jitter
  * @param maxDelay       upper bound on any single retry's wait —
  *                       protects against pathological `Retry-After`
  *                       values
  * @param jitter         jitter strategy applied to the computed backoff
  * @param retryTransient if `true` (the default), 5xx server errors
  *                       and transport-level exceptions are retried
  *                       per the method-safety rules described above.
  *                       Set to `false` to collapse to the conservative
  *                       "429 + 503 only" behaviour from earlier
  *                       releases.
  * @param onRetry        invoked once per retry decision, immediately
  *                       before the executor sleeps. The library never
  *                       logs on its own; this hook is the supported
  *                       way to surface retry activity to your logger
  *                       of choice. The callback runs synchronously
  *                       on the calling thread, and any exception it
  *                       throws is swallowed so a buggy logger cannot
  *                       break the request. The default is a no-op.
  */
final case class RetryPolicy(
  maxAttempts: Int = 5,
  baseDelay: FiniteDuration = 200.millis,
  maxDelay: FiniteDuration = 30.seconds,
  jitter: RetryPolicy.Jitter = RetryPolicy.Jitter.Full,
  retryTransient: Boolean = true,
  onRetry: RetryEvent => Unit = _ => ()
)

object RetryPolicy {

  /** Conservative production defaults: 5 attempts (4 retries), 200ms
    * base delay, 30s cap, full jitter.
    */
  val default: RetryPolicy = RetryPolicy()

  /** No retries — every request is attempted exactly once. Useful in
    * tests and for callers that want to manage retries themselves.
    */
  val noRetry: RetryPolicy = RetryPolicy(maxAttempts = 1)

  /** Jitter strategy applied to the computed backoff delay.
    *
    * "Full jitter" (Klaviyo's recommendation, and AWS's general
    * guidance) draws each retry delay uniformly from `[0, computed]`,
    * minimising the thundering-herd effect when many clients retry in
    * lockstep.
    */
  enum Jitter {

    /** Use the exponential backoff value as-is, with no randomisation. */
    case None

    /** Each retry delay is drawn uniformly from `[0, computed]`. */
    case Full
  }
}
