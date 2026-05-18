package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*

/** Webhooks category — full CRUD with a relationship payload.
  *
  * A webhook must be created with at least one `webhook-topic` it
  * subscribes to. We list available topics first, pick one, then
  * create / fetch / list / update / delete the webhook.
  */
object WebhooksDemo extends DemoApp {

  def category: String = "webhooks"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    section("list webhook topics") {
      val topics = client.webhooks.getWebhookTopics()
      log(s"account exposes ${topics.data.size} topic(s)")
      topics.data.take(5).foreach(t => log(s"  id=${t.id}"))
    }

    client.webhooks.getWebhookTopics().data.headOption.map(_.id) match {
      case None => log("no webhook topics returned by API; cannot create a webhook")
      case Some(topicId) => exerciseLifecycle(ctx, topicId)
    }
  }

  private def exerciseLifecycle(ctx: DemoCtx, topicId: String): Unit = {
    import ctx.*

    val webhookName = id("webhook")
    var createdId: String = null

    section("create webhook subscribed to one topic") {
      val resp = client.webhooks.createWebhook(
        WebhookCreateQuery(
          data = WebhookCreateQueryResourceObject(
            attributes = WebhookCreateQueryResourceObject.Attributes(
              name = webhookName,
              description = "klaviyo4s demo — safe to delete",
              endpointUrl = "https://example.invalid/webhook",
              secretKey = s"secret-$rand"
            ),
            relationships = WebhookCreateQueryResourceObject.Relationships(
              webhookTopics = WebhookCreateQueryResourceObject.Relationships.WebhookTopics(
                data = Vector(
                  WebhookCreateQueryResourceObject.Relationships.WebhookTopics.Data(
                    id = topicId
                  )
                )
              )
            )
          )
        )
      )
      createdId = resp.data.id
      register(s"deleteWebhook($createdId)") {
        client.webhooks.deleteWebhook(createdId)
      }
      log(s"created webhook id=$createdId subscribed to topic=$topicId")
    }

    section("fetch webhook by id") {
      val one = client.webhooks.getWebhook(createdId)
      log(s"endpoint=${one.data.attributes.endpointUrl}")
    }

    section("list webhooks (first page) and confirm visibility") {
      val all = client.webhooks.getWebhooks()
      log(s"page has ${all.data.size} webhook(s); created id present=${all.data.exists(_.id == createdId)}")
    }

    section("update webhook description") {
      client.webhooks.updateWebhook(
        id = createdId,
        body = WebhookPartialUpdateQuery(
          data = WebhookPartialUpdateQueryResourceObject(
            id = createdId,
            attributes = WebhookPartialUpdateQueryResourceObject.Attributes(
              description = "klaviyo4s demo — updated description"
            )
          )
        )
      )
      log("description updated")
    }
  }
}
