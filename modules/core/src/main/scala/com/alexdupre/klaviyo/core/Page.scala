package com.alexdupre.klaviyo.core

import com.alexdupre.klaviyo.core.jsonapi.CollectionLinks

/** Opaque alias for a Klaviyo pagination cursor.
  *
  * Klaviyo's cursor is an opaque base64-ish string supplied via the
  * `page[cursor]` query parameter; it is meaningful only to the server.
  * Wrapping it in an opaque type prevents accidental confusion with
  * unrelated strings (resource IDs, URLs, ...).
  */
opaque type Cursor = String

object Cursor {

  /** Wrap an existing cursor string. Used by the pagination helpers when
    * extracting `links.next` from a previous page.
    */
  inline def apply(value: String): Cursor = value

  /** Unwrap to the underlying string. Generated code calls this when
    * serialising the cursor into the `page[cursor]` query parameter.
    */
  extension (c: Cursor) inline def value: String = c
}

/** A single page of a paginated collection response.
  *
  * `Page` is what every generated "list" endpoint returns. The
  * [[com.alexdupre.klaviyo.core.Pagination]] helpers walk a sequence of
  * pages using [[nextCursor]] to drive the next request.
  *
  * @tparam T element type of the collection
  * @param data   items decoded from the response's `data` array
  * @param links  the JSON:API top-level links, primarily for `next`/`prev`
  * @param raw    response body verbatim, kept for diagnostics. Not
  *               displayed by `toString` to avoid leaking large bodies
  *               into logs by accident.
  */
final case class Page[T](
  data: Vector[T],
  links: CollectionLinks,
  raw: String
) {

  /** Extract the next-page cursor by parsing the `links.next` URL, if
    * present. Returns `None` when this is the last page.
    *
    * The cursor is recovered by looking for the `page[cursor]` query
    * parameter in the URL — Klaviyo always serialises it that way, so a
    * pure substring match suffices and we avoid pulling in a URL parser
    * just for this.
    */
  def nextCursor: Option[Cursor] =
    links.next.toOption.flatMap(Page.extractCursor)

  /** True iff `links.next` is present, i.e. another page exists.
    *
    * Strictly cheaper than [[nextCursor]] — does not parse the URL.
    */
  def hasNext: Boolean = links.next.isValue

  override def toString: String =
    s"Page(size=${data.size}, hasNext=$hasNext)"
}

object Page {

  /** Extract the `page[cursor]` parameter from a Klaviyo paginated URL.
    *
    * Klaviyo encodes the brackets as `%5B` / `%5D`, so both raw and
    * percent-encoded forms are recognised. Returns `None` if no cursor
    * is found — defensive in case Klaviyo's URL shape changes.
    *
    * Public so callers can lift cursors out of any `links.next` URL
    * — e.g. when threading a generated endpoint's response back into
    * itself for the next page.
    */
  def extractCursor(url: String): Option[Cursor] = {
    val keys = Seq("page[cursor]=", "page%5Bcursor%5D=")
    keys.iterator
      .map(k => url.indexOf(k) match { case -1 => None; case i => Some(i + k.length) })
      .collectFirst { case Some(start) => start }
      .map { start =>
        val end = {
          val amp = url.indexOf('&', start)
          if (amp < 0) url.length else amp
        }
        Cursor(java.net.URLDecoder.decode(url.substring(start, end), java.nio.charset.StandardCharsets.UTF_8))
      }
  }
}
