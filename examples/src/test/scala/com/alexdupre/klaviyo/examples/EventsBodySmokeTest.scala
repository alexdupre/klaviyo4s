package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*
import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.StatusCode
import sttp.shared.Identity

/** Smoke test for the request-body wiring (runtime gap #1).
  *
  * Builds a minimal `EventCreateQueryV2` and POSTs it via the
  * generated `client.events.createEvent` method. Asserts that
  *
  *   - the HTTP method is POST
  *   - the request body is the jsoniter-serialised JSON of the value
  *   - the `Content-Type` header is `application/vnd.api+json`
  *
  * Verifies the executor still applies auth + revision headers on
  * top of the user-supplied body — these were broken in earlier
  * iterations where the body path bypassed header injection.
  */
final class EventsBodySmokeTest extends munit.FunSuite {

  /** A minimal-but-valid Klaviyo event payload. Most attributes are
    * optional; only `metric` (with a name) and `profile` (with an
    * identifier) are functionally required by the API.
    */
  private val payload: EventCreateQueryV2 =
    EventCreateQueryV2(
      data = EventCreateQueryV2ResourceObject(
        attributes = EventCreateQueryV2ResourceObject.Attributes(
          properties = RawJson("{}"),
          value = 9.99d,
          metric = EventCreateQueryV2ResourceObject.Attributes.Metric(
            data = MetricCreateQueryResourceObject(
              attributes = MetricCreateQueryResourceObject.Attributes(name = "Viewed Product")
            )
          ),
          profile = EventCreateQueryV2ResourceObject.Attributes.Profile(
            data = EventProfileCreateQueryResourceObject(
              attributes = EventProfileCreateQueryResourceObject.Attributes(email = "alice@example.com")
            )
          )
        )
      )
    )

  test("createEvent serialises the body as application/vnd.api+json") {
    var capturedBody:        Option[String] = None
    var capturedContentType: Option[String] = None
    var capturedMethod:      Option[String] = None

    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { req =>
      capturedMethod      = Some(req.method.method)
      capturedContentType = req.headers.find(_.name.equalsIgnoreCase("Content-Type")).map(_.value)
      capturedBody        = req.body match {
        case sttp.client4.StringBody(s, _, _) => Some(s)
        case _                                => None
      }
      // The endpoint returns 202 (queued). The Executor maps 202 to
      // the planner's `successType` which for createEvent is `Unit`
      // (no body schema declared).
      ResponseStub.adjust("", StatusCode.Accepted)
    }

    val config = KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("secret"), revision = GeneratedSpecRevision)
    val client = new KlaviyoClient[Identity](stub, config)

    // Method returns F[Unit]; we just confirm it completes.
    client.events.createEvent(payload)

    assertEquals(capturedMethod, Some("POST"))
    assertEquals(capturedContentType, Some("application/vnd.api+json"))
    val body = capturedBody.getOrElse(fail("body was not a StringBody"))
    // Smoke-check structural shape. We could re-decode to assert
    // equality, but a substring match is cheap and pinpoints what
    // broke if the codec changes.
    assert(body.contains("\"type\":\"event\""), s"missing event type: $body")
    assert(body.contains("\"name\":\"Viewed Product\""), s"missing metric name: $body")
    assert(body.contains("\"email\":\"alice@example.com\""), s"missing profile email: $body")
    assert(body.contains("\"value\":9.99"), s"missing value: $body")
    // Absent fields should NOT appear in the JSON — this is the
    // Tristate omission semantic, verified end-to-end through a real
    // generated body type.
    assert(!body.contains("\"phone_number\""), s"phone_number should be omitted: $body")
    assert(!body.contains("\"backfill\""), s"backfill should be omitted: $body")
  }

  test("createEvent applies auth + revision headers alongside the body") {
    var headers: Map[String, String] = Map.empty
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { req =>
      headers = req.headers.map(h => h.name.toLowerCase -> h.value).toMap
      ResponseStub.adjust("", StatusCode.Accepted)
    }
    val client = new KlaviyoClient[Identity](stub, KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("k"), revision = GeneratedSpecRevision))
    client.events.createEvent(payload)

    assertEquals(headers.get("authorization"), Some("Klaviyo-API-Key k"))
    assertEquals(headers.get("content-type"), Some("application/vnd.api+json"))
    assert(headers.contains("revision"), s"missing revision header: $headers")
  }
}
