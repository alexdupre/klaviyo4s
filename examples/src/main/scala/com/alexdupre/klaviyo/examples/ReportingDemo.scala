package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*

/** Reporting category — analytics queries, no resources to clean up.
  *
  * Reports are computed on demand and don't produce persistent state,
  * so this demo is purely read-side. We pick the "Placed Order"
  * metric if it exists (the canonical conversion metric for most
  * Klaviyo stores) and query a few report shapes.
  */
object ReportingDemo extends DemoApp {

  def category: String = "reporting"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    // Reports require a "conversion metric" — the metric used to score
    // attribution. Most accounts have "Placed Order"; fall back to the
    // first metric available.
    val metrics = client.metrics.getMetrics()
    val conversionMetricIdOpt = metrics.data
      .find(_.attributes.name.contains("Placed Order"))
      .orElse(metrics.data.headOption)
      .map(_.id)

    conversionMetricIdOpt match {
      case None =>
        log("account has no metrics; reporting endpoints would 422")
      case Some(conversionMetricId) =>
        log(s"using conversion metric id=$conversionMetricId")
        runQueries(ctx, conversionMetricId)
    }
  }

  private def runQueries(ctx: DemoCtx, conversionMetricId: String): Unit = {
    import ctx.*

    // Klaviyo's reporting requests model `timeframe` as a `oneOf`
    // between a keyed preset (`{ "key": "last_30_days" }`) and a
    // custom date range (`{ "start": ..., "end": ... }`). The codegen
    // merges those into a single `TimeframeOrCustomTimeframe` record
    // where every field is optional; supplying just `key` matches the
    // preset shape. The same merged type is shared across every
    // reporting request that exposes a `timeframe` parameter.
    val last30Days = TimeframeOrCustomTimeframe(key = KeyEnum.Last30Days)

    // Statistics are typed enums now that the codegen synthesises a
    // named type from inline string-enum array items. The
    // value-set dedupe collapses identical inline enums across
    // the spec: `queryCampaignValues`, `queryFlowValues`, and the
    // matching `*Series` endpoints all share a single enum named
    // after the first contributor alphabetically, which is the
    // Campaign Values shape.
    import CampaignValuesRequestDTOResourceObjectAttributesStatisticsEnum as Stat
    val stats = Vector(Stat.Delivered, Stat.Opens, Stat.Clicks)

    section("query campaign values (last 30 days)") {
      val resp = client.reporting.queryCampaignValues(
        CampaignValuesRequestDTO(
          data = CampaignValuesRequestDTOResourceObject(
            attributes = CampaignValuesRequestDTOResourceObject.Attributes(
              statistics = stats,
              timeframe = last30Days,
              conversionMetricId = conversionMetricId
            )
          )
        )
      )
      log(s"got ${resp.data.attributes.results.size} campaign value row(s)")
    }

    section("query flow values (last 30 days)") {
      val resp = client.reporting.queryFlowValues(
        FlowValuesRequestDTO(
          data = FlowValuesRequestDTOResourceObject(
            attributes = FlowValuesRequestDTOResourceObject.Attributes(
              statistics = stats,
              timeframe = last30Days,
              conversionMetricId = conversionMetricId
            )
          )
        )
      )
      log(s"got ${resp.data.attributes.results.size} flow value row(s)")
    }
  }
}
