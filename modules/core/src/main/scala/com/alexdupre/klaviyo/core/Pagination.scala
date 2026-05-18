package com.alexdupre.klaviyo.core

import com.alexdupre.klaviyo.core.Tristate
import com.alexdupre.klaviyo.core.jsonapi.CollectionLinks
import sttp.monad.MonadError
import sttp.monad.syntax.*

/** Helpers for walking Klaviyo's cursor-paginated collection endpoints.
  *
  * Every generated "list"-style method takes a `pageCursor:
  * Tristate.Optional[String]` parameter and returns a response shape
  * with two relevant fields:
  *
  *   - `data: Vector[T]` — the page's elements
  *   - `links: Tristate.Optional[CollectionLinks]` — hypermedia
  *     including `links.next` for the next page
  *
  * The helpers below take a fetcher (`cursor => F[R]`) that captures
  * the user's filters/fields/sort and varies only on the cursor, plus
  * a projector that pulls the `(data, links)` pair out of the typed
  * response. Walking the cursor chain is then mechanical.
  *
  * Most users will reach for the [[KlaviyoClientPaginationOps]] extension
  * methods on `KlaviyoClient[F]` — those fix `F` from the receiver,
  * which sidesteps a Scala 3 inference quirk with `Identity` (since
  * `Identity[X] = X`, the compiler can't tell `F[R] = X` apart from a
  * bare `R`). The free functions on this object are still useful for
  * testing without a real client; callers there spell out the type
  * parameters.
  *
  * Two variants:
  *   - `collectAll` accumulates every page into a single `Vector`.
  *     Convenient when the total result set is bounded.
  *   - `foreachPage` runs a user-supplied side-effecting callback
  *     once per page. Memory stays flat regardless of total size, so
  *     this is the preferred shape for ETL-style traversals.
  *
  * v2 may add streaming variants (fs2 / ZIO Stream) for fully-lazy
  * traversal; v1 keeps the surface minimal.
  */
object Pagination {

  /** Walk every page of a generated paginated endpoint and accumulate
    * the elements into a single `Vector`.
    *
    * @param fetch    the generated method, partially applied so only
    *                 the cursor varies between calls. Pass other
    *                 query params explicitly inside the lambda.
    * @param extract  pulls `(data, links)` out of the response. The
    *                 links carry `.next`, from which we lift the
    *                 next page's cursor via the `nextCursor` extension
    *                 on [[com.alexdupre.klaviyo.core.jsonapi.CollectionLinks]].
    */
  def collectAll[F[_], R, T](
    fetch: Tristate.Optional[String] => F[R],
    extract: R => (Vector[T], Tristate.Optional[CollectionLinks])
  )(using me: MonadError[F]): F[Vector[T]] = {
    def loop(cursor: Tristate.Optional[String], acc: Vector[T]): F[Vector[T]] = {
      fetch(cursor).flatMap { resp =>
        val (data, links) = extract(resp)
        val combined = acc ++ data
        val next = links.toOption.flatMap(_.nextCursor).map(_.value)
        next match {
          case Some(c) => loop(Tristate.Value(c), combined)
          case None => me.unit(combined)
        }
      }
    }
    loop(Tristate.Absent, Vector.empty)
  }

  /** Walk every page of a generated paginated endpoint, invoking the
    * callback once per page. Use this for ETL-style traversals where
    * memory pressure matters — only one page is in memory at a time.
    *
    * The callback runs sequentially — a new page is not fetched
    * until the previous callback's `F[Unit]` completes.
    */
  def foreachPage[F[_], R, T](
    fetch: Tristate.Optional[String] => F[R],
    extract: R => (Vector[T], Tristate.Optional[CollectionLinks]),
    f: Vector[T] => F[Unit]
  )(using me: MonadError[F]): F[Unit] = {
    def loop(cursor: Tristate.Optional[String]): F[Unit] = {
      fetch(cursor).flatMap { resp =>
        val (data, links) = extract(resp)
        f(data).flatMap { _ =>
          val next = links.toOption.flatMap(_.nextCursor).map(_.value)
          next match {
            case Some(c) => loop(Tristate.Value(c))
            case None => me.unit(())
          }
        }
      }
    }
    loop(Tristate.Absent)
  }
}

/** Pagination helpers attached as extension methods on
  * [[KlaviyoClient]].
  *
  * Why this exists: when `F = Identity` (the synchronous backend),
  * `Identity[X]` is a type alias for `X`. A lambda returning a bare
  * value `R` therefore has type `F[R] = R`, but Scala 3's type
  * inference can't reverse that — it sees `R` and gives up on solving
  * for `F`. The extension fixes `F` from the receiver's type, so
  * inference only has to solve for `R` and `T`. Users on `Future`
  * never had the problem; `Identity` users get the same ergonomics
  * via the extension.
  *
  * Imported automatically with the rest of the umbrella; available
  * directly via `import com.alexdupre.klaviyo.core.*`.
  */
object KlaviyoClientPaginationOps {

  extension [F[_]](client: KlaviyoClient[F]) {

    /** See [[Pagination.collectAll]] — same semantics, with `F` fixed
      * by the receiving client to enable type inference for callers
      * on the synchronous `Identity` backend.
      */
    def collectAll[R, T](
      fetch: Tristate.Optional[String] => F[R],
      extract: R => (Vector[T], Tristate.Optional[CollectionLinks])
    ): F[Vector[T]] = {
      given MonadError[F] = client.backend.monad
      Pagination.collectAll(fetch, extract)
    }

    /** See [[Pagination.foreachPage]] — same semantics, with `F`
      * fixed by the receiving client.
      */
    def foreachPage[R, T](
      fetch: Tristate.Optional[String] => F[R],
      extract: R => (Vector[T], Tristate.Optional[CollectionLinks]),
      f: Vector[T] => F[Unit]
    ): F[Unit] = {
      given MonadError[F] = client.backend.monad
      Pagination.foreachPage(fetch, extract, f)
    }
  }
}

export KlaviyoClientPaginationOps.*
