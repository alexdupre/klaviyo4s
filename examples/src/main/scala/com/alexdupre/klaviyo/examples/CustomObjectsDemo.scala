package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*

/** Custom Objects category — data-source CRUD.
  *
  * "Custom objects" are Klaviyo's user-defined schemas attached to
  * profiles. The API surface is small: list/create/get/delete data
  * sources and bulk-create records. We exercise the data-source
  * lifecycle here; records would require a profile id to anchor to
  * and are skipped.
  */
object CustomObjectsDemo extends DemoApp {

  def category: String = "customobjects"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    val dsTitle = id("ds-title")
    var dsId: String = null

    section("create data source") {
      val resp = client.customobjects.createDataSource(
        DataSourceCreateQuery(
          data = DataSourceCreateQueryResourceObject(
            attributes = DataSourceCreateQueryResourceObject.Attributes(
              title = dsTitle,
              namespace = s"custom-objects",
              description = "klaviyo4s demo data source — safe to delete"
            )
          )
        )
      )
      dsId = resp.data.id
      register(s"deleteDataSource($dsId)") {
        client.customobjects.deleteDataSource(dsId)
      }
      log(s"created data source id=$dsId title=$dsTitle")
    }

    section("getDataSource by id") {
      val one = client.customobjects.getDataSource(dsId)
      log(s"namespace=${one.data.attributes.namespace}")
    }

    section("list data sources (first page)") {
      val all = client.customobjects.getDataSources()
      log(s"page has ${all.data.size} data source(s); created id present=${all.data.exists(_.id == dsId)}")
    }
  }
}
