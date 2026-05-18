package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*

/** Client category — public/storefront endpoints, all write-side and
  * unreversible.
  *
  * The `/api/client/...` endpoints are designed to be called from
  * the shopper's browser (or mobile app) using a public-key auth
  * flow, not the private key this demo carries. Calling them
  * server-side with a private key would let us create events,
  * profiles, subscriptions, push tokens, etc. — every one of them
  * an irreversible footprint on the account.
  *
  * We therefore never invoke these endpoints from this demo, even
  * with `--side-effects` set. The category is listed here for
  * documentation completeness; production code should hit them
  * from the user's device with a public-key SDK.
  */
object ClientDemo extends DemoApp {

  def category: String = "client"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*
    log("client endpoints are storefront-only and write irreversible profile/event data.")
    log("not invoked from this demo. See the per-method extensions on KlaviyoClient.")
  }
}
