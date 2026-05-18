package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*

/** Forms category — read-only.
  *
  * Form creation requires a `FormDefinition.versions` array of fully-
  * specified [[com.alexdupre.klaviyo.models.Version]] objects with
  * nested `Step`s, `Teaser`s, styles, etc. — constructing a valid one
  * from scratch is impractical for a demo, and any test form left on
  * the account would be visible in the Klaviyo UI. We exercise only
  * the read-side here.
  */
object FormsDemo extends DemoApp {

  def category: String = "forms"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    section("list forms (first page)") {
      val resp = client.forms.getForms()
      log(s"got ${resp.data.size} form(s)")
      resp.data.take(3).foreach { f =>
        log(s"  id=${f.id} name=${f.attributes.name}")
      }
    }

    section("get one form + its versions") {
      val resp = client.forms.getForms()
      resp.data.headOption match {
        case None => log("account has no forms; skipping")
        case Some(f) =>
          val one = client.forms.getForm(f.id)
          val versions = client.forms.getVersionsForForm(f.id)
          log(s"form ${f.id} has ${versions.data.size} version(s); status=${one.data.attributes.status}")
      }
    }
  }
}
