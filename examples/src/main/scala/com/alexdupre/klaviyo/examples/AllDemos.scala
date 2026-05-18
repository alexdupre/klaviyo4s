package com.alexdupre.klaviyo.examples

import scala.util.{Failure, Success, Try}

/** Runs every per-category demo back-to-back.
  *
  * Each demo is its own `DemoApp.main` and is invoked with the same
  * argv. Failures in one demo are caught and printed but do not stop
  * the run — the next demo still gets a chance. The harness inside
  * each demo runs its own cleanup, so a thrown exception still leads
  * to resource removal before control returns here.
  *
  * Invoke with `sbt 'examples/runMain com.alexdupre.klaviyo.examples.AllDemos'`
  * or pass `--side-effects` to enable the gated calls in each demo.
  */
object AllDemos {

  private val demos: Seq[DemoApp] = Seq(
    AccountsDemo,
    CampaignsDemo,
    CatalogsDemo,
    ClientDemo,
    ConversationsDemo,
    CouponsDemo,
    CustomObjectsDemo,
    DataPrivacyDemo,
    EventsDemo,
    FlowsDemo,
    FormsDemo,
    ImagesDemo,
    ListsDemo,
    MetricsDemo,
    ProfilesDemo,
    ReportingDemo,
    ReviewsDemo,
    SegmentsDemo,
    TagsDemo,
    TemplatesDemo,
    TrackingSettingsDemo,
    WebFeedsDemo,
    WebhooksDemo
  )

  def main(args: Array[String]): Unit = {
    val failed = scala.collection.mutable.ListBuffer.empty[(String, Throwable)]
    demos.foreach { demo =>
      println()
      println("=" * 72)
      println(s"=== ${demo.category}")
      println("=" * 72)
      Try(demo.main(args)) match {
        case Success(_) => ()
        case Failure(t) => failed += demo.category -> t
      }
    }
    println()
    println("=" * 72)
    if (failed.isEmpty) {
      println(s"all ${demos.size} demos completed without thrown errors")
    } else {
      println(s"${failed.size} demo(s) failed:")
      failed.foreach { case (cat, t) =>
        println(s"  - $cat: ${t.getClass.getSimpleName}: ${t.getMessage}")
      }
      sys.exit(1)
    }
  }
}
