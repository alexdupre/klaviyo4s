package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*

/** Events category — read-only.
  *
  * Klaviyo events are append-only: once created they cannot be
  * deleted. Creating a demo event would permanently put a row in
  * the account's event stream. We therefore only exercise reads
  * here; if you genuinely want to create an event from this code
  * use the generated `client.events.createEvent(...)` directly,
  * but be aware the entry cannot be removed afterwards.
  */
object EventsDemo extends DemoApp {

  def category: String = "events"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    section("list events (first page)") {
      val resp = client.events.getEvents()
      log(s"got ${resp.data.size} event(s)")
    }

    section("fetch a single event + its metric/profile relationships") {
      val resp = client.events.getEvents()
      resp.data.headOption match {
        case None => log("account has no events; skipping")
        case Some(e) =>
          val one = client.events.getEvent(e.id)
          val metric = client.events.getMetricForEvent(e.id)
          val profile = client.events.getProfileForEvent(e.id)
          val metricId = metric.data.map(_.id).getOrElse("?")
          val profileId = profile.data.flatMap(_.id).getOrElse("?")
          log(s"event ${one.data.id} → metric=$metricId profile=$profileId")
      }
    }

    if (sideEffectsEnabled)
      log("--side-effects on, but createEvent is irreversible; not invoked from this demo.")
  }
}
