package com.alexdupre.klaviyo.core.jsonapi

import com.alexdupre.klaviyo.core.Codecs.given
import com.alexdupre.klaviyo.core.Tristate
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/** JSON:API envelope types shared by every generated category client.
  *
  * Klaviyo's REST API speaks JSON:API (`application/vnd.api+json`):
  *
  * {{{
  * {
  *   "data": { "type": "...", "id": "...", "attributes": {...}, ... },
  *   "links": { "self": "..." , "next": "...", "prev": "..." },
  *   "included": [ ... ]
  * }
  * }}}
  *
  * Per-category modules generate concrete attribute / relationship types
  * and reuse these envelope shells, so we only define the envelope once.
  *
  * The types are intentionally polymorphic over the data and included
  * element types: the code generator wires the correct payload type for
  * each endpoint at emit time.
  *
  * The codec derivations rely on the `Codecs.given` primitive instances
  * imported at the top of this file, which is what makes the parameterised
  * `Tristate.codec[A]` resolve correctly for fields like `Tristate[String]`.
  */

/** Hypermedia links on a single resource (`data.links`). Always optional;
  * only `self` is meaningful for individual resources.
  */
final case class ResourceLinks(
  self: Tristate.Maybe[String] = Tristate.Absent
)

object ResourceLinks {
  given JsonValueCodec[ResourceLinks] = JsonCodecMaker.make
}

/** Top-level links on a collection document. `next`/`prev` drive
  * cursor-based pagination; `self` is informational.
  */
final case class CollectionLinks(
  self: Tristate.Maybe[String] = Tristate.Absent,
  next: Tristate.Maybe[String] = Tristate.Absent,
  prev: Tristate.Maybe[String] = Tristate.Absent,
  first: Tristate.Maybe[String] = Tristate.Absent,
  last: Tristate.Maybe[String] = Tristate.Absent
)

object CollectionLinks {
  given JsonValueCodec[CollectionLinks] = JsonCodecMaker.make

  /** Extract the next-page cursor from `links.next` if present.
    *
    * The `next` URL is opaque from the user's perspective — Klaviyo
    * decides its shape — but we know it always carries the cursor as
    * a `page[cursor]=…` query parameter (raw or percent-encoded).
    * This extension lifts that out so callers can re-feed it into
    * the next paginated call without parsing URLs by hand.
    */
  extension (cl: CollectionLinks)

    def nextCursor: Option[com.alexdupre.klaviyo.core.Cursor] =
      cl.next.toOption.flatMap(com.alexdupre.klaviyo.core.Page.extractCursor)
}

/** A single relationship object — the JSON:API construct that points from
  * one resource to another.
  *
  * `D` is the type of `data`. For one-to-one relationships it is a
  * [[ResourceIdentifier]]; for one-to-many it is `List[ResourceIdentifier]`.
  * The codegen materialises the concrete shape per endpoint.
  */
final case class Relationship[D](
  data: D,
  links: Tristate.Maybe[ResourceLinks] = Tristate.Absent
)

/** A JSON:API resource identifier: just `{type, id}`. Used as the payload
  * of [[Relationship]] when the related resource is referenced rather
  * than embedded.
  */
final case class ResourceIdentifier(
  `type`: String,
  id: String
)

object ResourceIdentifier {
  given JsonValueCodec[ResourceIdentifier] = JsonCodecMaker.make
}

/** Top-level JSON:API document.
  *
  * @tparam D the shape of `data` — a single resource, a list, or `null`
  *           for create-after-no-content responses
  * @tparam I the shape of an `included` element — a sealed union when the
  *           endpoint can return mixed resource types in `included`,
  *           otherwise a single resource type
  */
final case class Document[D, I](
  data: D,
  links: Tristate.Maybe[CollectionLinks] = Tristate.Absent,
  included: Tristate.Maybe[Vector[I]] = Tristate.Absent
)

/** Pointer to the part of the request that triggered the error. */
final case class ErrorSource(
  pointer: Tristate.Maybe[String] = Tristate.Absent,
  parameter: Tristate.Maybe[String] = Tristate.Absent,
  header: Tristate.Maybe[String] = Tristate.Absent
)

object ErrorSource {
  given JsonValueCodec[ErrorSource] = JsonCodecMaker.make
}

/** A single error object as defined by JSON:API §7.5.
  *
  * Klaviyo populates every field except occasionally `id` and `meta`. The
  * `source.parameter` sub-field localises which query/body parameter the
  * error refers to.
  */
final case class ErrorObject(
  id: Tristate.Maybe[String] = Tristate.Absent,
  /** HTTP status code applicable to this problem. JSON:API §7.5
    * specifies a string here, but Klaviyo emits it as a number
    * (`"status": 401`); we follow the wire format we observe.
    */
  status: Tristate.Maybe[Int] = Tristate.Absent,
  code: Tristate.Maybe[String] = Tristate.Absent,
  title: Tristate.Maybe[String] = Tristate.Absent,
  detail: Tristate.Maybe[String] = Tristate.Absent,
  source: Tristate.Maybe[ErrorSource] = Tristate.Absent,
  meta: Tristate.Maybe[Map[String, String]] = Tristate.Absent
)

object ErrorObject {
  given JsonValueCodec[ErrorObject] = JsonCodecMaker.make
}

/** Top-level error document: a non-empty array of error objects under
  * the `errors` key. Returned for every non-2xx response Klaviyo emits.
  */
final case class ErrorDocument(
  errors: List[ErrorObject]
)

object ErrorDocument {
  given JsonValueCodec[ErrorDocument] = JsonCodecMaker.make
}
