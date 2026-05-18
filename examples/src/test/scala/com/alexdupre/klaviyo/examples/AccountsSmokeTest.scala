package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*
import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.StatusCode
import sttp.shared.Identity

/** End-to-end smoke test for the generated `klaviyo4s-accounts` module.
  *
  * Verifies the whole codegen → emit → compile → run pipeline by
  * stubbing a realistic Klaviyo `GET /api/accounts/{id}` response and
  * checking that
  *
  *   - the extension method `client.accounts` resolves
  *   - the generated method builds the right URL + headers
  *   - the codec round-trips the JSON:API envelope
  *   - the typed result has the expected fields
  *
  * If any layer of the stack regresses this test fails loudly. It
  * intentionally uses the `Identity` backend so the assertions read
  * synchronously.
  */
final class AccountsSmokeTest extends munit.FunSuite {

  /** A representative Klaviyo `GET /api/accounts/{id}` response. The
    * structure mirrors what the live API returns — JSON:API envelope
    * with `data.type/id/attributes/links`. Optional fields are
    * intentionally a mix of present, null, and omitted so we exercise
    * the `Tristate` codec end-to-end.
    */
  private val sampleResponse: String =
    """{
      |  "data": {
      |    "type": "account",
      |    "id": "ACME1",
      |    "attributes": {
      |      "test_account": false,
      |      "contact_information": {
      |        "default_sender_name": "Acme Co",
      |        "default_sender_email": "noreply@acme.example",
      |        "website_url": null,
      |        "organization_name": "Acme Co",
      |        "street_address": {
      |          "city": "Boston"
      |        }
      |      },
      |      "industry": "Retail",
      |      "timezone": "US/Eastern",
      |      "preferred_currency": "USD",
      |      "public_api_key": "AbC123",
      |      "locale": "en-US"
      |    },
      |    "links": {
      |      "self": "https://a.klaviyo.com/api/accounts/ACME1"
      |    }
      |  }
      |}""".stripMargin

  test("client.accounts.getAccount decodes a JSON:API envelope end-to-end") {
    val stub: sttp.client4.SyncBackend = SyncBackendStub.whenAnyRequest.thenRespondF { req =>
      // Sanity-check the generated request shape.
      assert(req.uri.toString.endsWith("/api/accounts/ACME1"), s"unexpected uri: ${req.uri}")
      assertEquals(req.method.method, "GET")
      assert(req.headers.exists(h => h.name.equalsIgnoreCase("Authorization") && h.value == "Klaviyo-API-Key secret"))
      assert(req.headers.exists(h => h.name.equalsIgnoreCase("revision")))
      ResponseStub.adjust(sampleResponse, StatusCode.Ok)
    }

    val config: KlaviyoConfig = KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("secret"), revision = GeneratedSpecRevision)
    val client: KlaviyoClient[Identity] = new KlaviyoClient(stub, config)

    val resp = client.accounts.getAccount("ACME1")
    assertEquals(resp.data.id, "ACME1")
    val attrs = resp.data.attributes
    assertEquals(attrs.testAccount, false)
    // `industry` is nullable in the spec → Tristate[String] in Scala.
    // Compare to a Tristate.Value rather than the raw string.
    assertEquals(attrs.industry, Tristate.Value("Retail"))
    assertEquals(attrs.publicApiKey, "AbC123")

    // The optional `website_url` came back as JSON null, so it should
    // decode to Tristate.Null — not Absent and not a Value.
    val contact = attrs.contactInformation
    assertEquals(contact.websiteUrl, Tristate.Null)

    // The optional `region` field was omitted from the wire entirely.
    // It must decode to Tristate.Absent, distinct from Null.
    assertEquals(contact.streetAddress.region, Tristate.Absent)

    // The `links.self` field is present; check it survived the
    // shared-schema indirection that points at the core type.
    val selfLink = resp.data.links.self
    assertEquals(selfLink, Tristate.Value("https://a.klaviyo.com/api/accounts/ACME1"))
  }

  test("client.accounts.getAccounts works with a sparse-fieldset query parameter") {
    val collection =
      """{
        |  "data": [
        |    {
        |      "type": "account",
        |      "id": "ACME1",
        |      "attributes": {
        |        "test_account": false,
        |        "contact_information": {
        |          "default_sender_name": "Acme Co",
        |          "default_sender_email": "noreply@acme.example",
        |          "organization_name": "Acme Co",
        |          "street_address": { "city": "Boston" }
        |        },
        |        "timezone": "US/Eastern",
        |        "preferred_currency": "USD",
        |        "public_api_key": "AbC123",
        |        "locale": "en-US"
        |      },
        |      "links": { "self": "https://a.klaviyo.com/api/accounts/ACME1" }
        |    }
        |  ],
        |  "links": {
        |    "self": "https://a.klaviyo.com/api/accounts?fields%5Baccount%5D=public_api_key"
        |  }
        |}""".stripMargin

    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { req =>
      // The bracket-style param name MUST survive the camelCase
      // round-trip — the value goes on the wire under its original
      // `fields[account]` key (URL-encoded as `fields%5Baccount%5D`).
      val uriStr = req.uri.toString
      assert(
        uriStr.contains("fields%5Baccount%5D=public_api_key") || uriStr.contains("fields[account]=public_api_key"),
        s"missing bracket param in uri: $uriStr"
      )
      ResponseStub.adjust(collection, StatusCode.Ok)
    }

    val client = new KlaviyoClient[Identity](stub, KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("k"), revision = GeneratedSpecRevision))
    val resp   = client.accounts.getAccounts(fieldsAccount = Vector(FieldsAccountEnum.PublicApiKey))
    assertEquals(resp.data.size, 1)
    assertEquals(resp.data.head.id, "ACME1")
  }
}
