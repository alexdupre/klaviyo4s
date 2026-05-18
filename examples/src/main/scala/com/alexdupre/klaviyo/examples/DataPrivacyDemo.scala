package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*

/** Data Privacy category — only one endpoint, and it is destructive.
  *
  * `requestProfileDeletion` triggers permanent deletion of a profile
  * and all its associated event history. There is no inverse.
  * This demo therefore never invokes it; we only document that the
  * endpoint exists. Even with `--side-effects`, calling it would
  * require a real profile id whose deletion is genuinely intended.
  */
object DataPrivacyDemo extends DemoApp {

  def category: String = "dataprivacy"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*
    log("dataprivacy.requestProfileDeletion is irreversible — not invoked from this demo.")
    log("see KlaviyoClient extension method `client.dataprivacy.requestProfileDeletion(...)`")
    log("if you need to fire it manually against a profile you own.")
  }
}
