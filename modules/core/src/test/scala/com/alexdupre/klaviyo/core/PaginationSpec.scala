package com.alexdupre.klaviyo.core

import com.alexdupre.klaviyo.core.Tristate
import com.alexdupre.klaviyo.core.jsonapi.CollectionLinks
import sttp.client4.testing.SyncBackendStub
import sttp.monad.MonadError
import sttp.shared.Identity

/** Exercises [[Pagination.collectAll]] / [[Pagination.foreachPage]]
  * against a stand-in for a generated response shape — a small
  * `Resp` case class with `data: Vector[String]` and
  * `links: Tristate.Optional[CollectionLinks]`, which is exactly the
  * pattern the codegen produces for any `Get*Response*Collection*`
  * envelope.
  */
final class PaginationSpec extends munit.FunSuite {

  // We only need a `MonadError[Identity]` for the helpers; constructing
  // a tiny backend stub gets us one without ceremony.
  private given MonadError[Identity] = SyncBackendStub.monad

  /** Generated-response stand-in: deliberately matches the structural
    * shape (`data: Vector[T]`, `links: Tristate.Optional[CollectionLinks]`)
    * that the codegen emits for every collection endpoint.
    */
  private final case class Resp(
      data: Vector[String],
      links: Tristate.Optional[CollectionLinks] = Tristate.Absent
  )

  private def resp(items: Seq[String], nextCursor: Option[String]): Resp = {
    val links = CollectionLinks(
      next = nextCursor.fold[Tristate.Maybe[String]](Tristate.Absent)(c => Tristate.Value(s"https://x?page[cursor]=$c"))
    )
    Resp(items.toVector, Tristate.Value(links))
  }

  test("collectAll walks every page in order") {
    val pages = Iterator(
      resp(Seq("a", "b"), Some("p2")),
      resp(Seq("c", "d"), Some("p3")),
      resp(Seq("e"), None)
    )
    val all = Pagination.collectAll[Identity, Resp, String](
      _ => pages.next(),
      r => (r.data, r.links)
    )
    assertEquals(all, Vector("a", "b", "c", "d", "e"))
  }

  test("collectAll passes the right cursor to each subsequent call") {
    val seen  = collection.mutable.ListBuffer.empty[Tristate.Optional[String]]
    val pages = Iterator(
      resp(Seq("a"), Some("p2")),
      resp(Seq("b"), None)
    )
    Pagination.collectAll[Identity, Resp, String](
      cursor => { seen += cursor; pages.next() },
      r => (r.data, r.links)
    )
    assertEquals(seen.toList, List(Tristate.Absent, Tristate.Value("p2")))
  }

  test("collectAll terminates immediately when first page is the last") {
    var calls = 0
    val all = Pagination.collectAll[Identity, Resp, String](
      _ => { calls += 1; resp(Seq("only"), None) },
      r => (r.data, r.links)
    )
    assertEquals(all, Vector("only"))
    assertEquals(calls, 1)
  }

  test("foreachPage invokes the callback once per page") {
    val pages = Iterator(
      resp(Seq("a"), Some("p2")),
      resp(Seq("b"), Some("p3")),
      resp(Seq("c"), None)
    )
    val seen = collection.mutable.ListBuffer.empty[Vector[String]]
    val unit = Pagination.foreachPage[Identity, Resp, String](
      _ => pages.next(),
      r => (r.data, r.links),
      items => { seen += items; () }
    )
    assertEquals(unit, ())
    assertEquals(seen.toList, List(Vector("a"), Vector("b"), Vector("c")))
  }
}
