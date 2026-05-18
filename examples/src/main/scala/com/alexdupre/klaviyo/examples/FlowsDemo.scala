package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*

/** Flows category — read-only.
  *
  * Flows are normally created via the Klaviyo UI (visual builder).
  * The API can create draft flows, but the JSON definition is large
  * and version-sensitive; a malformed test flow is awkward to clean
  * up. We exercise only the read side: list, get, action listing.
  */
object FlowsDemo extends DemoApp {

  def category: String = "flows"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    section("list flows (first page)") {
      val resp = client.flows.getFlows()
      log(s"got ${resp.data.size} flow(s)")
      resp.data.take(3).foreach { f =>
        log(
          s"  id=${f.id} name=${f.attributes.name.toOption.getOrElse("?")} status=${f.attributes.status.toOption.getOrElse("?")}"
        )
      }
    }

    section("inspect first flow's actions") {
      val resp = client.flows.getFlows()
      resp.data.headOption match {
        case None => log("account has no flows; skipping")
        case Some(f) =>
          val one = client.flows.getFlow(f.id)
          val actions = client.flows.getActionsForFlow(f.id)
          log(s"flow ${one.data.id} has ${actions.data.size} action(s)")
      }
    }
  }
}
