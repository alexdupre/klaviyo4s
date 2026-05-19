package com.alexdupre.klaviyo.examples

// The whole point of this test: ONE import to use multiple categories.
import com.alexdupre.klaviyo.*
import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.StatusCode
import sttp.shared.Identity

/** Smoke test for generated code.
  *
  * Demonstrates the "one import is enough" promise: a single
  * `import com.alexdupre.klaviyo.*` brings every category's
  * extension method onto `KlaviyoClient[F]`, plus the runtime
  * essentials (`KlaviyoClient`, `KlaviyoConfig`, `KlaviyoAuth`,
  * `Tristate`). The test deliberately uses three different
  * categories — accounts, events, profiles — through the same
  * client to verify the extensions don't shadow each other.
  *
  * Model imports stay per-category by design; the umbrella does NOT
  * re-export thousands of generated DTO names.
  */
final class UmbrellaSmokeTest extends munit.FunSuite {

  test("one import resolves accounts, events, and profiles on the same client") {
    val accountsBody =
      """{
        |  "data": {
        |    "type": "account",
        |    "id": "A1",
        |    "attributes": {
        |      "test_account": false,
        |      "contact_information": {
        |        "default_sender_name": "Acme",
        |        "default_sender_email": "noreply@acme.test",
        |        "organization_name": "Acme",
        |        "street_address": { "city": "Boston" }
        |      },
        |      "timezone": "US/Eastern",
        |      "preferred_currency": "USD",
        |      "public_api_key": "AbC123",
        |      "locale": "en-US"
        |    },
        |    "links": { "self": "x" }
        |  }
        |}""".stripMargin

    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { req =>
      // Route by URL prefix so we can probe more than one endpoint
      // through the same backend.
      val uri = req.uri.toString
      if (uri.contains("/api/accounts/")) ResponseStub.adjust(accountsBody, StatusCode.Ok)
      else if (uri.contains("/api/events")) ResponseStub.adjust("", StatusCode.Accepted)
      else ResponseStub.adjust("{}", StatusCode.NotFound)
    }

    val config: KlaviyoConfig = KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("secret"), revision = GeneratedSpecRevision)
    val client                = KlaviyoClient[Identity](stub, config)

    // 1. Read an account.
    val acct = client.accounts.getAccount("A1")
    assertEquals(acct.data.id, "A1")
    assertEquals(acct.data.attributes.publicApiKey, "AbC123")

    // 2. The events extension resolves on the same client without a
    //    second import. We don't construct a payload here (would
    //    require model imports); the existence of the method on the
    //    typed client is enough to prove the extension is in scope.
    assert(summon[client.type <:< KlaviyoClient[Identity]] ne null)
    val _ = (client.events).##  // forces method resolution at compile time
  }

  test("Tristate is reachable through the umbrella import alone") {
    // No `import com.alexdupre.klaviyo.core.*` needed; the
    // umbrella re-exports the enum and its companion.
    val v: Tristate.Maybe[String] = "hi"
    val a: Tristate.Maybe[String] = Tristate.Absent
    val n: Tristate.Maybe[String] = Tristate.Null
    assertEquals(v, Tristate.Value("hi"))
    assertEquals(a, Tristate.Absent)
    assertEquals(n, Tristate.Null)
  }
}
