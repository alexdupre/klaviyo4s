package com.alexdupre.klaviyo.core

import scala.concurrent.duration.*

/** Configuration for [[com.alexdupre.klaviyo.core.KlaviyoClient]].
  *
  * Holds everything the runtime needs to build and retry requests, but
  * nothing transport-specific: this type lives in `klaviyo4s-core` so
  * applications can build a config without pulling in sttp.
  *
  * @param auth       authentication strategy — currently only
  *                   [[KlaviyoAuth.PrivateKey]] is implemented
  * @param revision   Klaviyo API revision string (`YYYY-MM-DD`) sent in
  *                   every request as the `revision` header. No
  *                   default — pass the constant the codegen emitted
  *                   for the spec your generated code was built
  *                   against (`com.alexdupre.klaviyo.GeneratedSpecRevision`).
  *                   Using a different revision than the one the
  *                   generated code targets will surface as
  *                   server-side validation errors against schemas
  *                   that changed between revisions.
  * @param baseUri    root URL for the Klaviyo API. Override for testing
  *                   against a stub or a regional endpoint.
  * @param retry      retry policy applied to every request. See
  *                   [[RetryPolicy]] for the supported strategies.
  * @param userAgent  User-Agent header sent with every request. Klaviyo
  *                   asks integrations to identify themselves here so
  *                   they can correlate traffic.
  * @param readTimeout how long to wait for a response on each
  *                    attempt. Triggers a `SocketTimeoutException` from
  *                    the sttp backend, which the executor surfaces as
  *                    [[KlaviyoError.Transport]] (or retries it on
  *                    idempotent methods per the [[RetryPolicy]]).
  *                    The default 30s comfortably covers every
  *                    documented Klaviyo workload (reporting, image
  *                    upload, paginated reads) — bulk operations are
  *                    explicitly async so don't tie up a request.
  *                    Connect timeout is not exposed here; configure
  *                    it on the sttp backend itself.
  */
final case class KlaviyoConfig(
  auth: KlaviyoAuth,
  revision: String,
  baseUri: String = KlaviyoConfig.DefaultBaseUri,
  retry: RetryPolicy = RetryPolicy.default,
  userAgent: String = KlaviyoConfig.DefaultUserAgent,
  readTimeout: FiniteDuration = 30.seconds
)

object KlaviyoConfig {

  /** Default Klaviyo API base URL. Klaviyo also exposes
    * `https://a.klaviyo.com` as the canonical host; the path prefix
    * `/api/...` is appended by each generated endpoint.
    */
  val DefaultBaseUri: String = "https://a.klaviyo.com"

  /** User-Agent identifier. The version is read from
    * `BuildInfo.version` (emitted by sbt-buildinfo at build time), so
    * the header automatically tracks the published library version
    * without a per-release manual bump. Tag your application's
    * User-Agent on top of this in production so Klaviyo support can
    * recognise your traffic.
    */
  val DefaultUserAgent: String = s"klaviyo4s/${BuildInfo.version}"
}
