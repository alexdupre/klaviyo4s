package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.StatusCode
import sttp.shared.Identity

/** Verifies that non-2xx responses parse into `KlaviyoError.Api`
  * with the JSON:API error list intact, and that the message
  * builder produces a useful summary for logs.
  */
final class ErrorBodyTest extends munit.FunSuite {

  test("401 with a JSON:API error doc surfaces as KlaviyoError.Api") {
    val body =
      """{
        |  "errors": [{
        |    "id": "1234",
        |    "status": 401,
        |    "code": "invalid_authentication",
        |    "title": "Invalid auth",
        |    "detail": "The API key supplied could not be authenticated"
        |  }]
        |}""".stripMargin

    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      ResponseStub.adjust(body, StatusCode.Unauthorized)
    }
    val client = new KlaviyoClient[Identity](
      stub,
      KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("bad-key"), revision = GeneratedSpecRevision)
    )

    val ex = intercept[KlaviyoError.Api] {
      val _ = client.accounts.getAccounts()
    }

    assertEquals(ex.status, 401)
    assertEquals(ex.errors.size, 1)
    val first = ex.errors.head
    assertEquals(first.code.toOption,   Some("invalid_authentication"))
    assertEquals(first.title.toOption,  Some("Invalid auth"))
    assert(ex.getMessage.contains("401"), s"status missing from message: ${ex.getMessage}")
    assert(ex.getMessage.contains("Invalid auth"), s"title missing from message: ${ex.getMessage}")
  }

  test("2xx with an unparseable body surfaces as KlaviyoError.Decode") {
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { _ =>
      ResponseStub.adjust("not valid JSON at all", StatusCode.Ok)
    }
    val client = new KlaviyoClient[Identity](
      stub,
      KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("k"), revision = GeneratedSpecRevision)
    )

    intercept[KlaviyoError.Decode] {
      val _ = client.accounts.getAccounts()
    }
  }
}
