package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*

/** Catalogs category — CRUD on a catalog item.
  *
  * Klaviyo catalogs model the merchant's product catalogue: items
  * are top-level entities, variants live underneath them, and
  * categories tie items together. The demo creates one item, reads
  * it, updates the price, and deletes it. Variants and categories
  * exercise the same lifecycle so we cover items as the
  * representative path.
  */
object CatalogsDemo extends DemoApp {

  def category: String = "catalogs"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    val extId = s"demo-item-$rand"
    var itemId: String = null

    section("create catalog item") {
      val resp = client.catalogs.createCatalogItem(
        CatalogItemCreateQuery(
          data = CatalogItemCreateQueryResourceObject(
            attributes = CatalogItemCreateQueryResourceObject.Attributes(
              externalId = extId,
              title = id("item-title"),
              description = "klaviyo4s demo item — safe to delete",
              url = "https://example.invalid/products/demo",
              price = 19.99
            )
          )
        )
      )
      itemId = resp.data.id
      register(s"deleteCatalogItem($itemId)") {
        client.catalogs.deleteCatalogItem(itemId)
      }
      log(s"created catalog item id=$itemId external_id=$extId")
    }

    section("getCatalogItem by id") {
      val one = client.catalogs.getCatalogItem(itemId)
      log(
        s"title=${one.data.attributes.title.toOption.getOrElse("?")} price=${one.data.attributes.price.toOption.getOrElse(0.0)}"
      )
    }

    section("list catalog items (first page)") {
      val all = client.catalogs.getCatalogItems()
      log(s"page has ${all.data.size} item(s); created id present=${all.data.exists(_.id == itemId)}")
    }

    section("update catalog item price") {
      client.catalogs.updateCatalogItem(
        id = itemId,
        body = CatalogItemUpdateQuery(
          data = CatalogItemUpdateQueryResourceObject(
            id = itemId,
            attributes = CatalogItemUpdateQueryResourceObject.Attributes(
              price = 29.99
            )
          )
        )
      )
      log("price updated to 29.99")
    }
  }
}
