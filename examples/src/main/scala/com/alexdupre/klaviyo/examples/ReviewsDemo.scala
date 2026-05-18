package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*

/** Reviews category — read-only.
  *
  * Reviews are created via Klaviyo's storefront integration, not the
  * server API. The category exposes only list/get/update endpoints on
  * existing reviews. We list reviews and fetch one if present.
  */
object ReviewsDemo extends DemoApp {

  def category: String = "reviews"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    section("list reviews (first page)") {
      val resp = client.reviews.getReviews()
      log(s"got ${resp.data.size} review(s)")
    }

    section("fetch first review by id (if any)") {
      val resp = client.reviews.getReviews()
      resp.data.headOption match {
        case None => log("account has no reviews; skipping")
        case Some(r) =>
          val one = client.reviews.getReview(r.id)
          log(s"review id=${one.data.id} status=${one.data.attributes.status.toOption.getOrElse("?")}")
      }
    }
  }
}
