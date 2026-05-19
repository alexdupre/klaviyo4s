package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*

/** Campaigns category — read-only.
  *
  * Campaign creation requires a structured `definition` payload
  * for each campaign message (HTML body + send strategy) that the
  * codegen currently models as `String` because of a Klaviyo spec
  * quirk; constructing a valid one from scratch is fragile.
  * `sendCampaign` is irreversible — it dispatches real messages to
  * the audience list — so even if a campaign were created cleanly,
  * actually triggering it from a demo would be unsafe.
  *
  * We exercise reads only: list campaigns, fetch one, list its
  * messages, ask for a recipient estimation.
  */
object CampaignsDemo extends DemoApp {

  def category: String = "campaigns"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    section("list campaigns matching 'Sent' status (filter DSL)") {
      // A `filter` value is required on getCampaigns.
      val filter = GetCampaignsFilter.messagesChannel.equals("email")
      val resp = client.campaigns.getCampaigns(filter = filter)
      log(s"got ${resp.data.size} campaign(s)")
      resp.data.take(3).foreach { c =>
        log(s"  id=${c.id} name=${c.attributes.name} status=${c.attributes.status}")
      }
    }

    section("fetch first campaign + recipient estimation") {
      val filter = GetCampaignsFilter.messagesChannel.equals("email")
      val resp = client.campaigns.getCampaigns(filter = filter)
      resp.data.headOption match {
        case None => log("account has no campaigns; skipping")
        case Some(c) =>
          val one = client.campaigns.getCampaign(c.id)
          val est = scala.util.Try(client.campaigns.getCampaignRecipientEstimation(c.id))
          val size = est.map(_.data.attributes.estimatedRecipientCount).getOrElse(0)
          log(s"campaign ${one.data.id} estimated recipients=$size")
      }
    }
  }
}
