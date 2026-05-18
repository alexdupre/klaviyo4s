package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*

/** Templates category — full CRUD on email templates.
  *
  * Creates a `CODE` editor-type template with a tiny HTML body,
  * fetches and updates it, renders it server-side (without a real
  * profile context), and deletes it.
  */
object TemplatesDemo extends DemoApp {

  def category: String = "templates"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    val templateName = id("template")
    var createdId: String = null

    section("create template") {
      val resp = client.templates.createTemplate(
        TemplateCreateHtmlOrDndQuery(
          data = TemplateCreateHtmlOrDndQueryResourceObject(
            attributes = TemplateCreateHtmlOrDndQueryResourceObject.Attributes(
              name = templateName,
              editorType = "CODE",
              html = "<html><body><p>Hello {{ first_name|default:'friend' }}</p></body></html>",
              text = "Hello {{ first_name|default:'friend' }}"
            )
          )
        )
      )
      createdId = resp.data.id
      register(s"deleteTemplate($createdId)") {
        client.templates.deleteTemplate(createdId)
      }
      log(s"created template id=$createdId name=$templateName")
    }

    section("getTemplate by id") {
      val one = client.templates.getTemplate(createdId)
      log(s"editor_type=${one.data.attributes.editorType}")
    }

    section("list templates (first page) and confirm visibility") {
      val all = client.templates.getTemplates()
      log(s"page has ${all.data.size} template(s); created id present=${all.data.exists(_.id == createdId)}")
    }

    section("update template name") {
      client.templates.updateTemplate(
        id = createdId,
        body = TemplateUpdateHtmlOrDndQuery(
          data = TemplateUpdateHtmlOrDndQueryResourceObject(
            id = createdId,
            attributes = TemplateUpdateHtmlOrDndQueryResourceObject.Attributes(
              name = s"$templateName-renamed"
            )
          )
        )
      )
      log("renamed")
    }

    section("clone template (and register cleanup)") {
      val cloneResp = client.templates.cloneTemplate(
        TemplateCloneQuery(
          data = TemplateCloneQueryResourceObject(
            id = createdId,
            attributes = TemplateCloneQueryResourceObject.Attributes(
              name = id("template-clone")
            )
          )
        )
      )
      val cloneId = cloneResp.data.id
      register(s"deleteTemplate($cloneId) [clone]") {
        client.templates.deleteTemplate(cloneId)
      }
      log(s"cloned id=$cloneId")
    }
  }
}
