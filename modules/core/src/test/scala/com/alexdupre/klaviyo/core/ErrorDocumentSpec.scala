package com.alexdupre.klaviyo.core

import com.alexdupre.klaviyo.core.jsonapi.{ErrorDocument, ErrorObject}
import com.github.plokhotnyuk.jsoniter_scala.core.*

/** Verifies the JSON:API error envelope decodes from representative
  * Klaviyo 4xx payloads, with every documented field populated.
  *
  * Sample payloads mirror what the live Klaviyo API actually emits
  * — see https://developers.klaviyo.com/en/docs/rate_limits_and_error_handling.
  * Important wire detail: Klaviyo emits the JSON:API `status` field
  * as a **number** (`"status": 401`) rather than the spec-mandated
  * string. Our model accepts numbers; this test regression-locks
  * that behaviour.
  */
final class ErrorDocumentSpec extends munit.FunSuite {

  test("decodes a fully-populated 4xx envelope (status as number)") {
    val json =
      """{
        |  "errors": [
        |    {
        |      "id": "abcd-1234",
        |      "status": 400,
        |      "code": "invalid_input",
        |      "title": "Invalid input",
        |      "detail": "filter parameter is malformed",
        |      "source": { "parameter": "filter" },
        |      "meta": { "trace_id": "xyz" }
        |    }
        |  ]
        |}""".stripMargin

    val doc = readFromString[ErrorDocument](json)
    assertEquals(doc.errors.size, 1)
    val e = doc.errors.head
    assert(e.id.contains("abcd-1234"))
    assert(e.status.contains(400))
    assert(e.code.contains("invalid_input"))
    assert(e.title.contains("Invalid input"))
    assert(e.detail.contains("filter parameter is malformed"))
    assert(e.source.flatMap(_.parameter).contains("filter"))
    assertEquals(e.meta.toOption.flatMap(_.get("trace_id")), Some("xyz"))
  }

  test("decodes the canonical 401 response Klaviyo returns for a bad key") {
    // Captured verbatim from a real 401 against `/api/accounts/` with
    // an invalid private key — the body that motivated the fix.
    val json =
      """{"errors":[{"id":"cc68708d-d834-4314-9cb4-cfe515b6bd6e","status":401,"code":"not_authenticated","title":"Authentication credentials were not provided.","detail":"Missing or invalid private key.","source":{"pointer":"/data/"}}]}"""

    val doc = readFromString[ErrorDocument](json)
    val e   = doc.errors.head
    assert(e.status.contains(401))
    assert(e.code.contains("not_authenticated"))
    assert(e.detail.contains("Missing or invalid private key."))
    assert(e.source.flatMap(_.pointer).contains("/data/"))
  }

  test("decodes a sparse error envelope without choking on missing keys") {
    val json = """{ "errors": [ { "status": 503 } ] }"""
    val doc  = readFromString[ErrorDocument](json)
    assert(doc.errors.head.status.contains(503))
    assertEquals(doc.errors.head.detail, Tristate.Absent)
    assertEquals(doc.errors.head.source, Tristate.Absent)
  }

  test("re-encodes an ErrorObject without writing absent fields") {
    val e   = ErrorObject(status = 429, title = "Too Many Requests")
    val out = writeToString(e)
    // Only `status` and `title` should appear on the wire; the
    // number is preserved as a JSON number, not stringified.
    assertEquals(out, """{"status":429,"title":"Too Many Requests"}""")
  }

  test("KlaviyoError.Api builds a useful message from real Klaviyo payload") {
    val rawBody =
      """{"errors":[{"id":"cc","status":401,"code":"not_authenticated","title":"Authentication credentials were not provided.","detail":"Missing or invalid private key.","source":{"pointer":"/data/"}}]}"""
    val errors = readFromString[ErrorDocument](rawBody).errors
    val ex     = new KlaviyoError.Api(401, errors, rawBody)
    val msg    = ex.getMessage
    // The message should surface both the human-readable title and
    // the operator-actionable detail — not "<no detail>".
    assert(msg.contains("status=401"), s"bad message: $msg")
    assert(msg.contains("Missing or invalid private key"), s"bad message: $msg")
    assert(!msg.contains("<no detail>"), s"bad message: $msg")
  }

  test("KlaviyoError.Api falls back to the raw body when nothing parses") {
    // Malformed body — errors list comes back empty (no fields parse).
    val rawBody = "<html>500 Server Error from upstream</html>"
    val ex      = new KlaviyoError.Api(500, errors = Nil, rawBody = rawBody)
    val msg     = ex.getMessage
    assert(msg.contains("status=500"))
    assert(msg.contains("500 Server Error from upstream"))
  }
}
