package com.alexdupre.klaviyo.core

import scala.concurrent.duration.FiniteDuration
import sttp.client4.Request

/** A retry decision the executor is about to act on.
  *
  * Delivered to [[RetryPolicy.onRetry]] once per retry, immediately
  * before the executor sleeps. Carries everything the user typically
  * wants for logging or metrics — which attempt is being retried, the
  * computed sleep, and *why* the executor chose to retry. The library
  * does not log on its own; this hook is the only supported way to
  * observe retry activity.
  *
  * @param request the request that just failed.
  * @param attempt the attempt number that just failed (1 = first
  *                request, 2 = the first retry that itself failed,
  *                etc.). The next request, if it runs, is attempt
  *                `attempt + 1`.
  * @param delay   how long the executor will sleep before the next
  *                attempt. Already capped at [[RetryPolicy.maxDelay]]
  *                and jittered if [[RetryPolicy.jitter]] is `Full`.
  * @param reason  what triggered the retry; see [[RetryEvent.Reason]]
  */
final case class RetryEvent(
  request: Request[String],
  attempt: Int,
  delay: FiniteDuration,
  reason: RetryEvent.Reason
)

object RetryEvent {

  /** Why the executor decided to retry. The four cases mirror the
    * four retry paths in the executor: `RateLimited` for `HTTP 429`,
    * `ServiceUnavailable` for `HTTP 503`, `ServerError` for the
    * `500 / 502 / 504` family retried only on idempotent methods,
    * and `Transport` for retried transport exceptions.
    */
  enum Reason {

    /** The server returned `HTTP 429`. If a `Retry-After` header was
      * present and within [[RetryPolicy.maxDelay]] the executor
      * honours it verbatim; otherwise (no header, or the value
      * exceeded `maxDelay` and the executor chose to fail fast) the
      * `retryAfter` field is `None`.
      */
    case RateLimited(retryAfter: Option[FiniteDuration])

    /** The server returned `HTTP 503`. Klaviyo guarantees a 503 means
      * the request was rejected, so this is retried regardless of
      * the HTTP method.
      */
    case ServiceUnavailable

    /** The server returned `HTTP 500 / 502 / 504`. Only emitted for
      * idempotent methods when [[RetryPolicy.retryTransient]] is
      * `true`; the actual status code is carried so the callback can
      * distinguish them if it cares.
      */
    case ServerError(status: Int)

    /** A transport-layer exception occurred — connection refused, TLS
      * handshake failure, DNS error, read timeout. Pre-send failures
      * are retried regardless of method; post-send failures are
      * retried only for idempotent methods.
      */
    case Transport(cause: Throwable)
  }
}
