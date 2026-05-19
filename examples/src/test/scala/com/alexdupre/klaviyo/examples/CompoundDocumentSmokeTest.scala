package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.models.*
import com.github.plokhotnyuk.jsoniter_scala.core.*

/** Smoke test for compound-document discriminated unions (runtime gap #4).
  *
  * `GetProfileResponseCompoundDocument` has an `included` array of
  * `oneOf(ListResponseObjectResource, SegmentResponseObjectResource,
  * ConversationResponseObjectResource, PushTokenResponseObjectResource)`
  * — Klaviyo's typical compound document for `?include=lists,segments`.
  * The codec must read the JSON:API `type` field and dispatch the
  * full decode to the matching variant's inner codec.
  *
  * This test runs decode against a hand-crafted mixed-type
  * `included` array and asserts that each variant comes back with
  * the correct concrete case + payload.
  */
final class CompoundDocumentSmokeTest extends munit.FunSuite {

  /** Minimal compound document with two included variants —
    * deliberately mixed and out-of-order so the dispatch is
    * non-trivial. Keep the inner payloads minimal: just the JSON:API
    * envelope skeleton (`type`/`id`), which is what the discriminator
    * codec really exercises.
    */
  /** Each included variant carries the minimum required fields its
    * inner type declares. `segment` and `push-token` have a handful
    * of required booleans / strings; `list` has none. Optional
    * fields are intentionally absent so the round-trip exercises the
    * Tristate omission path as well.
    */
  private val sampleJson: String =
    """{
      |  "data": {
      |    "type": "profile",
      |    "id": "P1",
      |    "attributes": {},
      |    "links": { "self": "https://example.com/profiles/P1" }
      |  },
      |  "included": [
      |    {
      |      "type": "segment", "id": "S1",
      |      "attributes": { "is_active": true, "is_processing": false, "is_starred": false },
      |      "links": { "self": "x" }
      |    },
      |    {
      |      "type": "list", "id": "L1",
      |      "attributes": {},
      |      "links": { "self": "x" }
      |    },
      |    {
      |      "type": "segment", "id": "S2",
      |      "attributes": { "is_active": false, "is_processing": false, "is_starred": true },
      |      "links": { "self": "x" }
      |    },
      |    {
      |      "type": "push-token", "id": "T1",
      |      "attributes": {
      |        "created": "2024-01-01T00:00:00Z",
      |        "token": "fcm-token-abc",
      |        "platform": "android",
      |        "vendor": "FCM",
      |        "enablement_status": "AUTHORIZED",
      |        "background": "AVAILABLE",
      |        "recorded_date": "2024-01-01T00:00:00Z"
      |      },
      |      "links": { "self": "x" }
      |    }
      |  ]
      |}""".stripMargin

  test("compound document decodes each `included` variant into the right case") {
    val doc      = readFromString[GetProfileResponseCompoundDocument](sampleJson)
    val included = doc.included.getOrElse(fail("included was Absent"))
    assertEquals(included.size, 4)

    included(0) match {
      case GetProfileResponseCompoundDocument.Included.SegmentVariant(v) => assertEquals(v.id, "S1")
      case other                                                        => fail(s"expected SegmentVariant, got $other")
    }
    included(1) match {
      case GetProfileResponseCompoundDocument.Included.ListVariant(v) => assertEquals(v.id, "L1")
      case other                                                     => fail(s"expected ListVariant, got $other")
    }
    included(2) match {
      case GetProfileResponseCompoundDocument.Included.SegmentVariant(v) => assertEquals(v.id, "S2")
      case other                                                        => fail(s"expected SegmentVariant, got $other")
    }
    // `push-token` discriminator (with the hyphen) survives intact.
    included(3) match {
      case GetProfileResponseCompoundDocument.Included.PushTokenVariant(v) => assertEquals(v.id, "T1")
      case other                                                          => fail(s"expected PushTokenVariant, got $other")
    }
  }
}
