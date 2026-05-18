package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*

/** Lists category — full CRUD + relationships.
  *
  * Creates a list, fetches it, lists everything, updates the name,
  * checks the (empty) profile/tag relationships, and deletes it.
  * Resource cleanup is registered immediately after creation so a
  * mid-demo failure still removes the list.
  */
object ListsDemo extends DemoApp {

  def category: String = "lists"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    val listName = id("list")
    var createdId: String = null

    section("create list") {
      val resp = client.lists.createList(
        ListCreateQuery(
          data = ListCreateQueryResourceObject(
            attributes = ListCreateQueryResourceObject.Attributes(name = listName)
          )
        )
      )
      createdId = resp.data.id
      register(s"deleteList($createdId)") {
        client.lists.deleteList(createdId)
      }
      log(s"created list id=$createdId name=$listName")
    }

    section("getList by id") {
      val one = client.lists.getList(createdId)
      log(s"name=${one.data.attributes.name.toOption.getOrElse("?")}")
    }

    section("list all lists (first page)") {
      val all = client.lists.getLists()
      log(s"page has ${all.data.size} list(s); created id present=${all.data.exists(_.id == createdId)}")
    }

    section("update list name") {
      client.lists.updateList(
        id = createdId,
        body = ListPartialUpdateQuery(
          data = ListPartialUpdateQueryResourceObject(
            id = createdId,
            attributes = ListPartialUpdateQueryResourceObject.Attributes(name = s"$listName-renamed")
          )
        )
      )
      log("name updated")
    }

    section("inspect relationships (no membership yet)") {
      val tags = client.lists.getTagsForList(createdId)
      val profiles = client.lists.getProfilesForList(createdId)
      log(s"tags=${tags.data.size}  profiles=${profiles.data.size}  (both expected 0)")
    }
  }
}
