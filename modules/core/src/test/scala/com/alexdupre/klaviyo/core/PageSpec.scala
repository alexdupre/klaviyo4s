package com.alexdupre.klaviyo.core

import com.alexdupre.klaviyo.core.jsonapi.CollectionLinks

/** Tests for cursor extraction. Klaviyo serialises the cursor in two
  * forms (raw brackets and percent-encoded) and the helper has to handle
  * both, plus tail-of-query placement and absence-of-trailing-params.
  */
final class PageSpec extends munit.FunSuite {

  test("extracts cursor from raw-bracket URL") {
    val url    = "https://a.klaviyo.com/api/profiles?page[cursor]=ABCDEF"
    val cursor = Page.extractCursor(url)
    assertEquals(cursor.map(_.value), Some("ABCDEF"))
  }

  test("extracts cursor from percent-encoded URL") {
    val url    = "https://a.klaviyo.com/api/profiles?page%5Bcursor%5D=ABCDEF"
    val cursor = Page.extractCursor(url)
    assertEquals(cursor.map(_.value), Some("ABCDEF"))
  }

  test("stops at the next query parameter separator") {
    val url    = "https://a.klaviyo.com/api/profiles?page[cursor]=ABC&filter=foo"
    val cursor = Page.extractCursor(url)
    assertEquals(cursor.map(_.value), Some("ABC"))
  }

  test("returns None when no cursor parameter is present") {
    val url    = "https://a.klaviyo.com/api/profiles?filter=foo"
    val cursor = Page.extractCursor(url)
    assertEquals(cursor, None)
  }

  test("Page.nextCursor reads from links.next") {
    val page = Page[String](
      data = Vector("a", "b"),
      links = CollectionLinks(next = Tristate.Value("https://x?page[cursor]=NEXT")),
      raw = ""
    )
    assertEquals(page.nextCursor.map(_.value), Some("NEXT"))
    assert(page.hasNext)
  }

  test("Page.hasNext is false when links.next is absent") {
    val page = Page[String](data = Vector.empty, links = CollectionLinks(), raw = "")
    assert(!page.hasNext)
    assertEquals(page.nextCursor, None)
  }
}
