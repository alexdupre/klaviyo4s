package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*
import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.StatusCode
import sttp.shared.Identity

/** End-to-end smoke test for the envelope-shaped pagination helpers.
  *
  * Exercises the canonical user pattern:
  *
  * {{{
  * val all = Pagination.collectAll(
  *   c => client.lists.getLists(pageCursor = c, pageSize = 100L)
  * )(r => (r.data, r.links))
  * }}}
  *
  * The stub returns three pages, each carrying a `links.next` URL
  * with a `page[cursor]=…` parameter. The helper must walk all
  * three, accumulate the elements, and stop when `links.next` is
  * absent.
  */
final class PaginationSmokeTest extends munit.FunSuite {

  /** Build a list-collection JSON page with `n` items and an optional
    * next-page cursor encoded in `links.next`.
    */
  private def listPage(itemIds: Seq[String], nextCursor: Option[String]): String = {
    val items = itemIds.map { id =>
      s"""{
         |  "type": "list",
         |  "id": "$id",
         |  "attributes": {
         |    "name": "List-$id",
         |    "created": "2024-01-01T00:00:00Z",
         |    "updated": "2024-01-01T00:00:00Z",
         |    "opt_in_process": "single_opt_in"
         |  },
         |  "links": { "self": "x" }
         |}""".stripMargin
    }.mkString(",")
    val nextLine = nextCursor match {
      case Some(c) => s""", "next": "https://a.klaviyo.com/api/lists?page%5Bcursor%5D=$c""""
      case None    => ""
    }
    s"""{
       |  "data": [$items],
       |  "links": { "self": "x"$nextLine }
       |}""".stripMargin
  }

  test("Pagination.collectAll walks every page and accumulates results") {
    val callsSeen = scala.collection.mutable.ListBuffer.empty[String]

    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { req =>
      val uri = req.uri.toString
      // Record which cursor (if any) was requested so we can verify
      // the helper threads them correctly.
      val cursor =
        if (uri.contains("page%5Bcursor%5D=")) {
          val s = uri.indexOf("page%5Bcursor%5D=") + "page%5Bcursor%5D=".length
          val e = uri.indexOf("&", s) match { case -1 => uri.length; case n => n }
          Some(uri.substring(s, e))
        } else if (uri.contains("page[cursor]=")) {
          val s = uri.indexOf("page[cursor]=") + "page[cursor]=".length
          val e = uri.indexOf("&", s) match { case -1 => uri.length; case n => n }
          Some(uri.substring(s, e))
        } else None
      callsSeen += cursor.getOrElse("<first>")

      val body = cursor match {
        case None         => listPage(Seq("L1", "L2"), nextCursor = Some("c2"))
        case Some("c2")   => listPage(Seq("L3", "L4"), nextCursor = Some("c3"))
        case Some("c3")   => listPage(Seq("L5"), nextCursor = None)
        case Some(other)  => fail(s"unexpected cursor: $other")
      }
      ResponseStub.adjust(body, StatusCode.Ok)
    }

    val client = new KlaviyoClient[Identity](stub, KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("k"), revision = GeneratedSpecRevision))

    val all: Vector[ListListResponseObjectResource] =
      client.collectAll(
        cursor => client.lists.getLists(pageCursor = cursor, pageSize = 100),
        r => (r.data, r.links)
      )

    // Three pages, five items total, in spec order.
    assertEquals(all.map(_.id), Vector("L1", "L2", "L3", "L4", "L5"))

    // Three calls: one without cursor, one with c2, one with c3.
    assertEquals(callsSeen.toList, List("<first>", "c2", "c3"))
  }

  test("Pagination.foreachPage runs the callback once per page") {
    val stub = SyncBackendStub.whenAnyRequest.thenRespondF { req =>
      val uri = req.uri.toString
      val body =
        if (uri.contains("page%5Bcursor%5D=c2") || uri.contains("page[cursor]=c2"))
          listPage(Seq("L3"), nextCursor = None)
        else
          listPage(Seq("L1", "L2"), nextCursor = Some("c2"))
      ResponseStub.adjust(body, StatusCode.Ok)
    }

    val client = new KlaviyoClient[Identity](stub, KlaviyoConfig(auth = KlaviyoAuth.PrivateKey("k"), revision = GeneratedSpecRevision))

    val seen = scala.collection.mutable.ListBuffer.empty[Vector[String]]
    client.foreachPage(
      cursor => client.lists.getLists(pageCursor = cursor),
      r => (r.data, r.links),
      items => { seen += items.map(_.id); () }
    )

    assertEquals(seen.toList, List(Vector("L1", "L2"), Vector("L3")))
  }
}
