package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*

/** Tags category — tag-group + tag CRUD.
  *
  * Klaviyo's tag model is two-level: every tag belongs to a tag group.
  * Klaviyo provides a default group, but to keep this demo isolated
  * we create our own group, create a tag inside it, exercise the
  * relationship endpoints (no actual tagging on entities), then
  * delete the tag and the group.
  */
object TagsDemo extends DemoApp {

  def category: String = "tags"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    var groupId: String = null
    var tagId: String = null

    section("create tag group") {
      val resp = client.tags.createTagGroup(
        TagGroupCreateQuery(
          data = TagGroupCreateQueryResourceObject(
            attributes = TagGroupCreateQueryResourceObject.Attributes(name = id("tag-group"))
          )
        )
      )
      groupId = resp.data.id
      register(s"deleteTagGroup($groupId)") {
        client.tags.deleteTagGroup(groupId)
      }
      log(s"created tag group id=$groupId")
    }

    section("create tag in group") {
      val resp = client.tags.createTag(
        TagCreateQuery(
          data = TagCreateQueryResourceObject(
            attributes = TagCreateQueryResourceObject.Attributes(name = id("tag")),
            relationships = TagCreateQueryResourceObject.Relationships(
              tagGroup = TagCreateQueryResourceObject.Relationships.TagGroup(
                data = TagCreateQueryResourceObject.Relationships.TagGroup.Data(
                  id = groupId
                )
              )
            )
          )
        )
      )
      tagId = resp.data.id
      register(s"deleteTag($tagId)") {
        client.tags.deleteTag(tagId)
      }
      log(s"created tag id=$tagId in group $groupId")
    }

    section("read back the tag") {
      val one = client.tags.getTag(tagId)
      log(s"tag name=${one.data.attributes.name}")
    }

    section("list tag groups (first page)") {
      val all = client.tags.getTagGroups()
      log(s"page has ${all.data.size} group(s); created group present=${all.data.exists(_.id == groupId)}")
    }

    section("list flow/list/campaign/segment ids tagged with our tag (should be empty)") {
      val onFlows = client.tags.getFlowIdsForTag(tagId).data.size
      val onLists = client.tags.getListIdsForTag(tagId).data.size
      val onCampaigns = client.tags.getCampaignIdsForTag(tagId).data.size
      val onSegments = client.tags.getSegmentIdsForTag(tagId).data.size
      log(s"flows=$onFlows lists=$onLists campaigns=$onCampaigns segments=$onSegments (all expected 0)")
    }
  }
}
