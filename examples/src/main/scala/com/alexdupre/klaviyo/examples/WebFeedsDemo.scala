package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*

/** Web feeds category — full CRUD.
  *
  * A web feed is a server-side fetched JSON/XML document used as a
  * data source for personalisation. The demo points one at an
  * inexpensive public JSON document, exercises the lifecycle, and
  * deletes it.
  */
object WebFeedsDemo extends DemoApp {

  def category: String = "webfeeds"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    val feedName = id("feed")
    var createdId: String = null

    section("create web feed") {
      val resp = client.webfeeds.createWebFeed(
        WebFeedCreateQuery(
          data = WebFeedCreateQueryResourceObject(
            attributes = WebFeedCreateQueryResourceObject.Attributes(
              name = feedName,
              url = "https://api.github.com/zen",
              requestMethod = RequestMethodEnum.Get,
              contentType = ContentTypeEnum.Json
            )
          )
        )
      )
      createdId = resp.data.id
      register(s"deleteWebFeed($createdId)") {
        client.webfeeds.deleteWebFeed(createdId)
      }
      log(s"created web feed id=$createdId name=$feedName")
    }

    section("getWebFeed by id") {
      val one = client.webfeeds.getWebFeed(createdId)
      log(s"url=${one.data.attributes.url}  status=${one.data.attributes.status.toOption.getOrElse("?")}")
    }

    section("list web feeds (first page)") {
      val all = client.webfeeds.getWebFeeds()
      log(s"page has ${all.data.size} feed(s); created id present=${all.data.exists(_.id == createdId)}")
    }

    section("update web feed name") {
      client.webfeeds.updateWebFeed(
        id = createdId,
        body = WebFeedPartialUpdateQuery(
          data = WebFeedPartialUpdateQueryResourceObject(
            id = createdId,
            attributes = WebFeedPartialUpdateQueryResourceObject.Attributes(
              name = s"$feedName-renamed"
            )
          )
        )
      )
      log("renamed")
    }
  }
}
