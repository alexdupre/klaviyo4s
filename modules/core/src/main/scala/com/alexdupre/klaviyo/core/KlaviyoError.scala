package com.alexdupre.klaviyo.core

import com.alexdupre.klaviyo.core.jsonapi.ErrorObject

import scala.concurrent.duration.FiniteDuration

/** Root of the klaviyo4s error hierarchy.
  *
  * `KlaviyoError` extends `RuntimeException` so it propagates through any
  * effect type via the effect's native error channel without ever being
  * wrapped in an `Either`:
  *   - `Identity` — raised by `throw`
  *   - `Future`   — surfaces via `Future.failed`
  *   - cats-effect `IO` — `IO.raiseError`
  *   - ZIO        — into the failure channel
  *
  * Generated method signatures therefore always read `F[A]`, never
  * `F[Either[KlaviyoError, A]]`. Callers can `try`/`catch`,
  * `recoverWith`, or pattern-match through their effect type's standard
  * error machinery.
  */
sealed abstract class KlaviyoError(message: String, cause: Throwable | Null) extends RuntimeException(message, cause)

object KlaviyoError {

  /** A non-2xx response was returned by Klaviyo and a JSON:API error
    * document was successfully parsed from the body.
    *
    * For 429 responses see the [[RateLimited]] subtype, which carries
    * the parsed `Retry-After` header alongside the standard payload.
    *
    * @param status     HTTP status code as returned by the server
    * @param errors     parsed `errors` array from the response body
    * @param rawBody    original response body, preserved verbatim for
    *                   diagnostics — never trim or pre-process it before
    *                   logging
    */
  class Api(
    val status: Int,
    val errors: List[ErrorObject],
    val rawBody: String
  ) extends KlaviyoError(Api.buildMessage(status, errors, rawBody), null)

  object Api {

    /** Compose the most useful single-line message we can given what
      * parsed out of the response. Falls back through several layers
      * so the exception is informative even when the error body
      * doesn't quite match our model:
      *
      *   1. first error's `title` + `: ` + `detail` (preferred when both are present)
      *   2. otherwise `detail` alone
      *   3. otherwise `title` alone
      *   4. otherwise `code` (machine identifier — still better than nothing)
      *   5. otherwise a truncated excerpt of the raw body
      *   6. otherwise just the status code
      *
      * The status code is always included up front so log scrubbers
      * and operators can spot it without parsing the message.
      */
    private[core] def buildMessage(status: Int, errors: List[ErrorObject], rawBody: String): String = {
      val head = errors.headOption
      val title = head.flatMap(_.title.toOption).filter(_.nonEmpty)
      val detail = head.flatMap(_.detail.toOption).filter(_.nonEmpty)
      val code = head.flatMap(_.code.toOption).filter(_.nonEmpty)
      val excerpt = Option(rawBody).map(_.trim).filter(_.nonEmpty).map { b =>
        if (b.length <= 200) b
        else {
          // Avoid slicing in the middle of a UTF-16 surrogate pair —
          // that produces a dangling high surrogate that is not valid
          // Unicode and can confuse downstream log consumers.
          val cut = if (Character.isHighSurrogate(b.charAt(199))) 199 else 200
          b.substring(0, cut) + "…"
        }
      }
      val tail = (title, detail) match {
        case (Some(t), Some(d)) => Some(s"$t: $d")
        case (Some(t), None) => Some(t)
        case (None, Some(d)) => Some(d)
        case (None, None) => code.map(c => s"code=$c").orElse(excerpt)
      }
      tail match {
        case Some(m) => s"Klaviyo API error (status=$status): $m"
        case None => s"Klaviyo API error (status=$status)"
      }
    }
  }

  /** Rate-limit response (`HTTP 429`).
    *
    * Klaviyo replaces the regular `RateLimit-*` headers with a single
    * `Retry-After` (integer seconds) when the limit is exhausted. The
    * executor honours this header for the in-flight request's retry
    * schedule; this subtype is raised only when retries are exhausted
    * so the caller can decide what to do next.
    */
  final class RateLimited(
    val retryAfter: FiniteDuration,
    errors: List[ErrorObject],
    rawBody: String
  ) extends Api(429, errors, rawBody)

  /** A network-layer failure occurred before a response could be
    * decoded — connection refused, timeout, TLS error, etc. The
    * underlying exception is preserved as the cause.
    */
  final class Transport(cause: Throwable)
      extends KlaviyoError(s"Klaviyo transport error: ${cause.getMessage}", cause)

  /** Klaviyo returned a 2xx but the body could not be decoded into the
    * expected Scala type. Usually indicates a spec drift; report and
    * regenerate the client.
    *
    * `rawBody` is preserved verbatim so the offending payload can be
    * captured in a bug report.
    */
  final class Decode(cause: Throwable, val rawBody: String)
      extends KlaviyoError(s"Klaviyo response decode error: ${cause.getMessage}", cause)
}
