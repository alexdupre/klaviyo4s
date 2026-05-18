package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*

/** Tracking settings category — read-only.
  *
  * The tracking-settings endpoints control account-wide UTM-tag
  * behaviour on email clicks. Mutating them affects every link
  * Klaviyo rewrites for the account, so the demo never invokes
  * `updateTrackingSetting`. We list and fetch the singleton record
  * to confirm the read paths.
  */
object TrackingSettingsDemo extends DemoApp {

  def category: String = "trackingsettings"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    section("list tracking settings") {
      val resp = client.trackingsettings.getTrackingSettings()
      log(s"got ${resp.data.size} tracking-setting record(s)")
    }

    section("fetch first tracking-setting record by id") {
      val resp = client.trackingsettings.getTrackingSettings()
      resp.data.headOption match {
        case None => log("no tracking settings exposed (unusual)")
        case Some(t) =>
          val one = client.trackingsettings.getTrackingSetting(t.id)
          log(s"setting id=${one.data.id} auto_add_parameters=${one.data.attributes.autoAddParameters}")
      }
    }

    if (sideEffectsEnabled)
      log("--side-effects on, but trackingsettings.updateTrackingSetting is account-wide; not invoked.")
  }
}
