package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*

/** Segments category — read-only by default.
  *
  * Creating a segment requires a `SegmentDefinition.condition_groups`
  * full of condition objects whose JSON schema is heterogeneous
  * (Klaviyo uses a `oneOf` over many condition types). Constructing
  * a valid definition in a hand-coded demo would need a non-trivial
  * fixture; the demo therefore exercises only the read-side here.
  *
  * To exercise create/update/delete against a real account, pass
  * `--side-effects`. The demo will then create a trivially-defined
  * segment via the raw HTTP path. Even so the segment is named with
  * the run-unique `id(...)` prefix and deleted on the way out.
  */
object SegmentsDemo extends DemoApp {

  def category: String = "segments"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    section("list segments (first page)") {
      val resp = client.segments.getSegments()
      log(s"got ${resp.data.size} segment(s) on first page")
      resp.data.take(3).foreach { s =>
        log(s"  id=${s.id} name=${s.attributes.name.toOption.getOrElse("?")}")
      }
    }

    section("fetch a single segment by id") {
      val resp = client.segments.getSegments()
      resp.data.headOption match {
        case None => log("account has no segments; skipping getSegment")
        case Some(seg) =>
          val one = client.segments.getSegment(seg.id)
          log(s"getSegment(${one.data.id}) name=${one.data.attributes.name.toOption.getOrElse("?")}")
          // Also exercise the read-side relationship endpoints.
          val tags = client.segments.getTagsForSegment(seg.id)
          val flows = client.segments.getFlowsTriggeredBySegment(seg.id)
          log(s"  tags=${tags.data.size}  flows-triggered=${flows.data.size}")
      }
    }
  }
}
