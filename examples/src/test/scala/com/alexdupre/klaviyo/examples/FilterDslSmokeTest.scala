package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.GetCampaignsFilter
import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.StatusCode
import sttp.shared.Identity

/** End-to-end smoke test for the typed filter DSL (Phase 5).
  *
  * Exercises the canonical user pattern:
  *
  * {{{
  * val f = GetCampaignsFilter.status.equals("Sent") and
  *         GetCampaignsFilter.createdAt.greaterOrEqual("2024-01-01T00:00:00Z")
  * client.campaigns.getCampaigns(filter = f)
  * }}}
  *
  * The stub captures the outgoing query string and we assert it
  * contains the expected wire-format filter expression.
  */
final class FilterDslSmokeTest extends munit.FunSuite {

  test("typed filter renders the wire format and reaches the request") {
    var seenUri: Option[String] = None
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { req =>
      seenUri = Some(req.uri.toString)
      // Return an empty campaign-collection response; the test only
      // cares about the outgoing request.
      ResponseStub.adjust("""{"data": [], "links": { "self": "x" }}""", StatusCode.Ok)
    }
    val client = new KlaviyoClient[Identity](stub, KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("k"), revision = GeneratedSpecRevision))

    val f: Filter =
      GetCampaignsFilter.status.equals("Sent") and
        GetCampaignsFilter.createdAt.greaterOrEqual(java.time.OffsetDateTime.parse("2024-01-01T00:00:00Z"))

    val _ = client.campaigns.getCampaigns(filter = f)

    val uri = seenUri.getOrElse(fail("no request captured"))
    // The URI may percent-encode the parentheses / commas; we
    // recover the raw filter value to assert its shape.
    val decoded = java.net.URLDecoder.decode(uri, java.nio.charset.StandardCharsets.UTF_8)
    // String values are double-quoted; datetimes are bare ISO-8601.
    // `OffsetDateTime.toString` produces the shortest form — trailing
    // zero seconds get elided, so `2024-01-01T00:00:00Z` round-trips
    // to `2024-01-01T00:00Z` (Klaviyo accepts both).
    assert(decoded.contains("""filter=equals(status,"Sent"),greater-or-equal(created_at,2024-01-01T00:00Z)"""),
      s"unexpected uri (decoded): $decoded")
  }

  test("string values use double quotes on the wire and get URI-encoded as %22") {
    var seenUri: Option[String] = None
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { req =>
      seenUri = Some(req.uri.toString)
      ResponseStub.adjust("""{"data": [], "links": { "self": "x" }}""", StatusCode.Ok)
    }
    val client = new KlaviyoClient[Identity](stub, KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("k"), revision = GeneratedSpecRevision))
    val _ = client.campaigns.getCampaigns(filter = GetCampaignsFilter.status.equals("Sent"))
    val uri = seenUri.getOrElse(fail("no request captured"))
    // Double quotes around the value end up as `%22` in the
    // emitted query string — sttp's URI builder percent-encodes
    // chars that aren't safe in query values (per RFC 3986).
    // Filter syntax (parens, commas) stays unencoded.
    assert(uri.contains("%22Sent%22"), s"expected %22Sent%22 in raw uri: $uri")
  }

  test("list-form operator emits comma-separated double-quoted values") {
    val f = GetCampaignsFilter.status.any("Sent", "Draft", "Cancelled")
    assertEquals(f.render, """any(status,"Sent","Draft","Cancelled")""")
  }

  test("equals overload disambiguation: single `equals` vs list `equalsIn`") {
    // `status` has BOTH single-form `equals` and list-form `equals`.
    // The list variant gets the `In` suffix; the single keeps its
    // bare name.
    val single = GetCampaignsFilter.status.equals("Sent")
    val list   = GetCampaignsFilter.status.equalsIn("Sent", "Draft")
    assertEquals(single.render, """equals(status,"Sent")""")
    assertEquals(list.render, """equals(status,"Sent","Draft")""")
  }

  test("string values are double-quoted and embedded double quotes are backslash-escaped") {
    val f = GetCampaignsFilter.name.contains("""Say "Hello"""")
    assertEquals(f.render, """contains(name,"Say \"Hello\"")""")
  }

  test("boolean values render bare (no quotes)") {
    val f = GetCampaignsFilter.archived.equals(false)
    assertEquals(f.render, "equals(archived,false)")
  }

  test("Filter.all AND-combines multiple filters with commas") {
    val a = GetCampaignsFilter.status.equals("Sent")
    val b = GetCampaignsFilter.archived.equals(false)
    val c = GetCampaignsFilter.name.contains("welcome")
    val combined: Filter = Filter.all(a, b, c)
    assertEquals(
      combined.render,
      """equals(status,"Sent"),equals(archived,false),contains(name,"welcome")"""
    )
  }
}
