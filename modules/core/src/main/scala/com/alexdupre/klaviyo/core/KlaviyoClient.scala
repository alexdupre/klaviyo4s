package com.alexdupre.klaviyo.core

import com.alexdupre.klaviyo.core.KlaviyoConfig
import sttp.client4.Backend

/** Single entry point for every klaviyo4s category.
  *
  * Holds the user-chosen sttp backend, the API configuration and the
  * `Sleep[F]` instance used by the retry policy. Generated per-category
  * modules attach themselves via Scala 3 `extension` methods on this
  * type:
  *
  * {{{
  * import com.alexdupre.klaviyo.accounts.given          // brings `.accounts`
  * import com.alexdupre.klaviyo.events.given            // brings `.events`
  *
  * val client = KlaviyoClient(backend, config)
  * client.accounts.getAccount("ACME")
  * client.events.createEvent(payload)
  * }}}
  *
  * Generated code ships an umbrella package that re-exports every
  * category's extension in one import.
  *
  * The class is intentionally minimal — no caches, no lazy state — so
  * users can hold multiple clients with different configs cheaply (e.g.
  * one per Klaviyo account in a multi-tenant deployment).
  */
final class KlaviyoClient[F[_]](
  val backend: Backend[F],
  val config: KlaviyoConfig
)(using val sleep: Sleep[F]) {

  /** sttp's `MonadError[F]`. Generated code uses this directly via
    * `import sttp.monad.syntax.*` rather than threading it through every
    * method signature.
    */
  given sttp.monad.MonadError[F] = backend.monad
}
