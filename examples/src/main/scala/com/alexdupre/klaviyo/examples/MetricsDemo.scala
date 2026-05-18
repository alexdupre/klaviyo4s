package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*

/** Metrics category — mostly read-only with optional custom-metric creation.
  *
  * Klaviyo metrics are system-managed: created lazily when events
  * arrive. The category does expose CRUD on *custom* metrics, which
  * is a separate concept; we demo that read-only by default and
  * create+delete a custom metric only when `--side-effects` is set.
  */
object MetricsDemo extends DemoApp {

  def category: String = "metrics"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    section("list metrics (first page)") {
      val resp = client.metrics.getMetrics()
      log(s"got ${resp.data.size} metric(s)")
      resp.data.take(3).foreach { m =>
        log(s"  id=${m.id} name=${m.attributes.name.toOption.getOrElse("?")}")
      }
    }

    section("inspect first metric's properties") {
      val metrics = client.metrics.getMetrics()
      metrics.data.headOption match {
        case None => log("no metrics on this account; skipping")
        case Some(m) =>
          val props = client.metrics.getPropertiesForMetric(m.id)
          log(s"metric ${m.id} has ${props.data.size} property/properties (first page)")
      }
    }

    section("list custom metrics") {
      val custom = client.metrics.getCustomMetrics()
      log(s"got ${custom.data.size} custom metric(s)")
    }

    section("list mapped metrics") {
      val mapped = client.metrics.getMappedMetrics()
      log(s"got ${mapped.data.size} mapped metric(s)")
    }
    // Note: creating a custom metric requires a `definition` referencing
    // existing metric ids; constructing a valid one in a demo is awkward
    // because it depends on the account's metric catalogue. We omit it.
  }
}
