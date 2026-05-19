package com.alexdupre.klaviyo.core

import com.github.plokhotnyuk.jsoniter_scala.core.*

import scala.language.implicitConversions

/** Explicit three-state field model, parameterised on which of the three
  * states are reachable for a given field.
  *
  * Klaviyo's REST API distinguishes three on-the-wire conditions for any
  * field, and the four (`required`, `nullable`) cells in the spec map to
  * four different reachable subsets:
  *
  *   - `required, !nullable`  → only `Value`        (bare `T` in the model)
  *   - `required,  nullable`  → `Null | Value`      ([[Tristate.Nullable]])
  *   - `!required, !nullable` → `Absent | Value`    ([[Tristate.Optional]])
  *   - `!required,  nullable` → all three           ([[Tristate.Maybe]])
  *
  * The phantom `S` type parameter tags each case with the state it
  * represents (via [[Tristate.States]]) so the compiler can reject
  * impossible assignments:
  *
  * {{{
  * import com.alexdupre.klaviyo.core.Tristate.{Optional, Maybe}
  *
  * val a: Optional[String] = "alice@example.com"   // OK — auto Value
  * val b: Optional[String] = Tristate.Absent       // OK
  * val c: Optional[String] = Tristate.Null         // compile error — Null not in Optional
  *
  * val d: Maybe[String]    = Tristate.Null         // OK — Null is in Maybe
  * }}}
  *
  * The implicit conversion `A → Tristate.Value[A]` works for every
  * cell because `States.Value` is in every union, so call sites that
  * just want to assign a real value never need to spell out the
  * wrapper.
  *
  * @tparam A the underlying value type when present
  * @tparam S phantom marker indicating which case kinds are reachable
  */
enum Tristate[+A, +S] {

  /** The field is omitted from the JSON document. The canonical default
    * for [[Tristate.Optional]] and [[Tristate.Maybe]] fields.
    */
  case Absent extends Tristate[Nothing, Tristate.States.Absent]

  /** The field is present and explicitly `null` in the JSON document.
    * Only reachable on [[Tristate.Nullable]] / [[Tristate.Maybe]].
    */
  case Null extends Tristate[Nothing, Tristate.States.Null]

  /** The field is present and carries the wrapped value. Reachable on
    * every alias.
    */
  case Value[+A](value: A) extends Tristate[A, Tristate.States.Value]

  /** Lossy projection to `Option`: returns `Some` only when this is a
    * [[Tristate.Value]], collapsing `Absent` and `Null` to `None`.
    */
  inline def toOption: Option[A] = this match {
    case Tristate.Value(a) => Some(a)
    case _ => None
  }

  /** `true` iff the field was present on the wire (either as a value
    * or as explicit `null`). The complement of `isAbsent`.
    */
  inline def isPresent: Boolean = this match {
    case Tristate.Absent => false
    case _ => true
  }

  /** `true` iff the field was omitted from the wire. */
  inline def isAbsent: Boolean = this match {
    case Tristate.Absent => true
    case _ => false
  }

  /** `true` iff the field carried an actual value (not omitted, not
    * null). The predicate users typically need to differentiate "I
    * have a real value" from "anything else".
    */
  inline def isValue: Boolean = this match {
    case _: Tristate.Value[?] => true
    case _ => false
  }

  /** Synonym for [[isValue]] that reads naturally next to `Option`-style
    * call sites (`if (t.nonEmpty) ...`).
    */
  inline def nonEmpty: Boolean = isValue

  /** `true` iff the field was explicitly `null` on the wire. */
  inline def isNull: Boolean = this match {
    case Tristate.Null => true
    case _ => false
  }

  /** Total eliminator. Each branch is forced to be supplied — useful
    * when the call site really needs to distinguish all three states
    * (e.g. PATCH semantics where `Null` means "clear this field" and
    * `Absent` means "leave it alone"). Most call sites that don't
    * care about the absent/null distinction can reach for [[get]] /
    * [[getOrElse]] / [[toOption]] instead.
    */
  inline def fold[B](onAbsent: => B, onNull: => B, onValue: A => B): B = this match {
    case Tristate.Absent => onAbsent
    case Tristate.Null => onNull
    case Tristate.Value(a) => onValue(a)
  }

  /** Return the wrapped value if this is [[Tristate.Value]], otherwise
    * throw a `NoSuchElementException` whose message identifies which
    * non-value state was encountered (`Absent` vs `Null`). Mirrors
    * `Option#get`'s shape — convenient when the caller is certain
    * the value is present, accepting a runtime failure if not.
    *
    * Prefer [[getOrElse]] or [[fold]] when the caller doesn't have
    * that certainty.
    */
  inline def get: A = this match {
    case Tristate.Value(a) => a
    case Tristate.Absent => throw new NoSuchElementException("Tristate.Absent.get")
    case Tristate.Null => throw new NoSuchElementException("Tristate.Null.get")
  }

  /** Return the wrapped value if this is [[Tristate.Value]], otherwise
    * the by-name `default`. Mirrors `Option#getOrElse`. The default
    * is only evaluated when needed, so it's safe to use expensive or
    * side-effectful fallbacks here.
    *
    * `B >: A` lets the caller widen the result type to a supertype
    * (typical when the fallback is `null` or a different concrete
    * subtype) without losing type inference on the value branch.
    */
  inline def getOrElse[B >: A](default: => B): B = this match {
    case Tristate.Value(a) => a
    case _ => default
  }

  /** `true` iff this is [[Tristate.Value]] AND its wrapped value
    * equals `elem` (`==`). Returns `false` for both `Absent` and
    * `Null`. Mirrors `Option#contains`.
    *
    * `A1 >: A` matches `Option#contains` — the comparand may be a
    * supertype, which is what you want when calling on a covariant
    * tristate (e.g. `tri.contains(null)` on a `Tristate[String]`).
    */
  inline def contains[A1 >: A](elem: A1): Boolean = this match {
    case Tristate.Value(a) => a == elem
    case _ => false
  }

  /** `true` iff this is [[Tristate.Value]] AND the predicate returns
    * `true` for its wrapped value. Returns `false` for both `Absent`
    * and `Null`. Mirrors `Option#exists`.
    */
  inline def exists(p: A => Boolean): Boolean = this match {
    case Tristate.Value(a) => p(a)
    case _ => false
  }

  /** Run `f` against the wrapped value if this is [[Tristate.Value]],
    * do nothing otherwise. Mirrors `Option#foreach`. The `U` return
    * type parameter lets `f` return anything; the result is
    * discarded. Useful for fold-free conditional effects:
    *
    * {{{
    * profile.attributes.email.foreach(send(_))
    * }}}
    */
  inline def foreach[U](f: A => U): Unit = this match {
    case Tristate.Value(a) => val _ = f(a)
    case _ => ()
  }

  /** Transform the inner value when present, leaving the other two
    * states untouched. The result widens `S` to the fully permissive
    * union — the typer rarely needs the original `S` once the value
    * has been transformed, and keeping it would require threading the
    * phantom through every callable.
    */
  inline def map[B](f: A => B): Tristate[B, Tristate.States.Maybe] = this match {
    case Tristate.Absent => Tristate.Absent
    case Tristate.Null => Tristate.Null
    case Tristate.Value(a) => Tristate.Value(f(a))
  }

  /** Sequence with another `Tristate`: when this is [[Tristate.Value]],
    * delegate to `f`; otherwise keep the current `Absent` / `Null`
    * state. Mirrors `Option#flatMap`.
    *
    * The result type is fixed to `Tristate.Maybe[B]` — the widest
    * union — for two reasons:
    *
    *   - The receiver might have been `Absent`, the function might
    *     have returned `Null`, or vice versa. The output set of
    *     reachable states is the union of both inputs, and that
    *     union is at most `Maybe`.
    *   - Trying to express "(this.S \ Value) | f-result.S" in the
    *     type system would force every caller to thread phantom
    *     states by hand. Widening once at the `flatMap` boundary
    *     matches what [[map]] already does and keeps user code
    *     readable.
    *
    * The function's return type accepts any `Tristate[B, S]` because
    * every concrete state subtypes `States.Maybe` via the phantom-
    * state covariance, so callers don't need an explicit widening
    * cast even when their function literally produces
    * `Tristate.Optional[B]` or `Tristate.Nullable[B]`.
    */
  inline def flatMap[B](f: A => Tristate[B, Tristate.States.Maybe]): Tristate[B, Tristate.States.Maybe] =
    this match {
      case Tristate.Absent => Tristate.Absent
      case Tristate.Null => Tristate.Null
      case Tristate.Value(a) => f(a)
    }

  /** Flatten a nested `Tristate`. When this is [[Tristate.Value]],
    * returns the inner tristate; otherwise propagates the receiver's
    * `Absent` / `Null` state. The result widens to [[Tristate.Maybe]]
    * for the same reason [[map]] / [[flatMap]] do — the output's
    * reachable states are the union of the outer and inner phantom
    * states, capped at `Maybe`.
    *
    * Equivalent to `flatMap(identity)` when the inner type aligns;
    * spelled out as a dedicated method so the implicit evidence
    * (`A <:< Tristate[B, S2]`) makes the "nested" precondition a
    * type-check rather than a runtime cast.
    */
  inline def flatten[B](using ev: A <:< Tristate[B, Tristate.States.Maybe]): Tristate[B, Tristate.States.Maybe] =
    this match {
      case Tristate.Absent => Tristate.Absent
      case Tristate.Null => Tristate.Null
      case Tristate.Value(a) => ev(a)
    }

  /** Keep [[Tristate.Value]] when the predicate holds; collapse to
    * [[Tristate.Absent]] when it does not. `Absent` and `Null`
    * receivers pass through unchanged — matching [[collect]]'s policy
    * that the "filtered out" output is `Absent` while a `Null` input
    * stays `Null`. Mirrors `Option#filter`.
    *
    * The result widens to [[Tristate.Maybe]] (same as [[map]] /
    * [[flatMap]] / [[collect]]): the output reaches `Value` (kept),
    * `Absent` (filtered or pass-through) or `Null` (pass-through).
    */
  inline def filter(p: A => Boolean): Tristate[A, Tristate.States.Maybe] = this match {
    case Tristate.Absent => Tristate.Absent
    case Tristate.Null => Tristate.Null
    case Tristate.Value(a) => if (p(a)) Tristate.Value(a) else Tristate.Absent
  }

  /** Complement of [[filter]] — keep [[Tristate.Value]] when the
    * predicate is FALSE. Same pass-through and widening rules.
    */
  inline def filterNot(p: A => Boolean): Tristate[A, Tristate.States.Maybe] = this match {
    case Tristate.Absent => Tristate.Absent
    case Tristate.Null => Tristate.Null
    case Tristate.Value(a) => if (!p(a)) Tristate.Value(a) else Tristate.Absent
  }

  /** For-comprehension guard hook. Scala 3 desugars `for { x <- t if
    * p(x) } yield ...` to `t.withFilter(p).map(...)`. We delegate to
    * [[filter]]; there is no laziness benefit to a dedicated
    * `WithFilter` class because a `Tristate` already carries at most
    * one element.
    */
  inline def withFilter(p: A => Boolean): Tristate[A, Tristate.States.Maybe] = filter(p)

  /** Return `this` when it is [[Tristate.Value]]; otherwise return
    * `alternative`. The by-name `alternative` is only evaluated on
    * `Absent` / `Null` receivers, so a fallback can safely be an
    * expensive computation or a side-effectful default.
    *
    * The phantom state is threaded precisely as `States.Value | S2`:
    * the result is reachable either through the receiver's `Value`
    * branch or through whatever states the alternative declares. In
    * particular, `Optional[A] orElse Optional[A]` stays
    * `Optional[A]`, and `Maybe[A] orElse Value[A]` stays `Maybe[A]`
    * — no forced widening to `Maybe` like [[map]] / [[flatMap]].
    *
    * Mirrors `Option#orElse`.
    */
  inline def orElse[B >: A, S2](alternative: => Tristate[B, S2]): Tristate[B, Tristate.States.Value | S2] =
    this match {
      case Tristate.Value(a) => Tristate.Value(a)
      case _ => alternative
    }

  /** Iterator over the wrapped value. Yields a single element on
    * [[Tristate.Value]], empty on `Absent` / `Null`. Useful when
    * splicing a `Tristate` into a `for`-comprehension over an
    * `Iterable` or for `flatMap`-style spread into collections.
    */
  inline def iterator: Iterator[A] = this match {
    case Tristate.Value(a) => Iterator.single(a)
    case _ => Iterator.empty
  }

  /** Single-element `List` projection. `Value(a) -> List(a)`; both
    * `Absent` and `Null` collapse to `Nil`.
    */
  inline def toList: List[A] = this match {
    case Tristate.Value(a) => a :: Nil
    case _ => Nil
  }

  /** Single-element `Vector` projection. `Value(a) -> Vector(a)`;
    * both `Absent` and `Null` collapse to `Vector.empty`. Pair this
    * with the existing pagination helpers when concatenating optional
    * pages.
    */
  inline def toVector: Vector[A] = this match {
    case Tristate.Value(a) => Vector(a)
    case _ => Vector.empty
  }

  /** Apply a partial function to the wrapped value: when this is
    * [[Tristate.Value]] and `pf` is defined at the inner value,
    * return `Value(pf(a))`; when `pf` is NOT defined at the inner
    * value, collapse to [[Tristate.Absent]]. `Absent` and `Null`
    * receivers pass through unchanged. Mirrors `Option#collect`.
    *
    * `Absent` is the natural "no value" result for the "filtered
    * out" case because it pairs with `Option.None` everywhere else
    * in this API (`Option.toOptional`, `toOption`, …). If you'd
    * rather emit `Null` for that case (e.g. for a PATCH endpoint
    * where clearing is the intended effect), use [[fold]] or
    * [[flatMap]] to express it explicitly.
    *
    * The result widens to [[Tristate.Maybe]] — same widening
    * policy as [[map]] / [[flatMap]] — because the output can
    * reach any of the three states (`Null` survives a Null
    * receiver, `Absent` comes from either an Absent receiver or
    * the "PF undefined" branch, `Value` comes from a matching
    * `Value` receiver).
    */
  inline def collect[B](pf: PartialFunction[A, B]): Tristate[B, Tristate.States.Maybe] =
    this match {
      case Tristate.Absent => Tristate.Absent
      case Tristate.Null => Tristate.Null
      case Tristate.Value(a) =>
        if (pf.isDefinedAt(a)) Tristate.Value(pf(a))
        else Tristate.Absent
    }
}

object Tristate {

  /** Phantom-state tags carried by each [[Tristate]] case. They have
    * no runtime representation; they exist only at the type level so
    * the compiler can express "only some cases are reachable here".
    */
  object States {
    sealed trait Absent
    sealed trait Null
    sealed trait Value

    /** The set of states reachable for `!required, !nullable` fields:
      * absent or value, never null.
      */
    type Optional = Absent | Value

    /** The set of states reachable for `required, nullable` fields:
      * the field is always present (so never `Absent`) but its value
      * may be JSON null.
      */
    type Nullable = Null | Value

    /** The set of states reachable for `!required, nullable` fields:
      * absent, null, or value.
      */
    type Maybe = Absent | Null | Value
  }

  /** Type alias for `!required, !nullable` fields — the field is
    * either omitted from the JSON or carries an actual value, but
    * never explicit null. Equivalent to `Tristate[A, States.Optional]`.
    *
    * Assigning `Tristate.Null` to an `Optional[A]` is a compile error
    * — the type's allowed states do not include null.
    */
  type Optional[+A] = Tristate[A, States.Optional]

  /** Type alias for `required, nullable` fields — the field is always
    * present on the wire and its value is either JSON null or a real
    * value. Equivalent to `Tristate[A, States.Nullable]`.
    *
    * Assigning `Tristate.Absent` to a `Nullable[A]` is a compile error
    * — the field can never be omitted.
    */
  type Nullable[+A] = Tristate[A, States.Nullable]

  /** Type alias for `!required, nullable` fields — fully permissive,
    * accepting any of the three states. Equivalent to
    * `Tristate[A, States.Maybe]`.
    */
  type Maybe[+A] = Tristate[A, States.Maybe]

  /** Implicit conversion from a raw value to a `Tristate.Value(...)`,
    * so call sites do not need the wrapper. Target is the most
    * specific `Tristate.Value[A]` — which subsumes into every alias
    * because `States.Value` is in every union.
    *
    * {{{
    * val v: Optional[String] = "x@y.z"   // -> Tristate.Value("x@y.z")
    * val n: Nullable[String] = "x@y.z"   // works for Nullable too
    * }}}
    *
    * The conversion is intentionally narrow — only the `Value`
    * direction is auto-applied, so a user cannot accidentally
    * collapse `Null` or `Absent` to a value.
    */
  given valueFromA[A]: Conversion[A, Tristate.Value[A]] = a => Tristate.Value(a)

  /** Lift an `Option` into the various `Tristate` aliases.
    *
    * `Optional[A]` and `Nullable[A]` each have a single "missing"
    * state, so the lift is unambiguous and there is one method per
    * alias. `Maybe[A]` has two missing states (`Absent` and `Null`),
    * so we offer three lifts: two policy-pinned variants
    * ([[toMaybeAbsent]], [[toMaybeNull]]) and one fully-encoded
    * variant ([[toMaybe]]) that takes `Option[Option[A]]` to
    * encode all three states without information loss.
    *
    * Lives in the `Tristate` companion so the extensions are picked
    * up automatically wherever the aliases are already in scope —
    * no extra import.
    */
  extension [A](opt: Option[A]) {

    /** `Some(a)` → `Value(a)`; `None` → `Absent`. The only sensible
      * lift for an `!required, !nullable` field.
      */
    inline def toOptional: Tristate.Optional[A] = opt match {
      case Some(a) => Tristate.Value(a)
      case None => Tristate.Absent
    }

    /** `Some(a)` → `Value(a)`; `None` → `Null`. The only sensible lift
      * for a `required, nullable` field — Klaviyo's spec marks the
      * field as always present on the wire, so a missing input must
      * serialise as JSON `null`, not be omitted.
      */
    inline def toNullable: Tristate.Nullable[A] = opt match {
      case Some(a) => Tristate.Value(a)
      case None => Tristate.Null
    }

    /** `Some(a)` → `Value(a)`; `None` → `Absent`. Use when the wire
      * convention is "prefer omission over explicit null" — i.e.
      * leave the field out of the JSON when the user has no value.
      * The most common policy for `Maybe[A]` fields.
      */
    inline def toMaybeAbsent: Tristate.Maybe[A] = opt match {
      case Some(a) => Tristate.Value(a)
      case None => Tristate.Absent
    }

    /** `Some(a)` → `Value(a)`; `None` → `Null`. Use when the wire
      * convention demands explicit `null` for a missing value —
      * e.g. a PATCH endpoint where `null` means "clear this field"
      * and omission means "leave it alone".
      */
    inline def toMaybeNull: Tristate.Maybe[A] = opt match {
      case Some(a) => Tristate.Value(a)
      case None => Tristate.Null
    }
  }

  /** Lossless lift from a doubly-wrapped option to [[Maybe]].
    *
    * The outer layer distinguishes "field is missing entirely" from
    * "field is present", and the inner layer (when present)
    * distinguishes JSON `null` from a real value:
    *
    *   - `None`           → [[Tristate.Absent]]
    *   - `Some(None)`     → [[Tristate.Null]]
    *   - `Some(Some(a))`  → `Tristate.Value(a)`
    *
    * This is the isomorphism between `Option[Option[A]]` and the
    * three-state `Maybe[A]`; useful when callers already have data
    * in that shape (e.g. from a separate decoder layer) and want to
    * project losslessly. For the policy-pinned lifts from a plain
    * `Option[A]`, see [[toMaybeAbsent]] / [[toMaybeNull]] above.
    *
    * Placed at object level (rather than as an extension on
    * `Option[Option[A]]`) so it stays unambiguous next to the inner
    * `extension [A](opt: Option[A])` — Scala 3 would otherwise pick
    * the inner extension and lift the outer `Some(...)` into
    * `Value(Some(...))`.
    */
  extension [A](opt: Option[Option[A]]) {

    inline def toMaybe: Tristate.Maybe[A] = opt match {
      case None => Tristate.Absent
      case Some(None) => Tristate.Null
      case Some(Some(a)) => Tristate.Value(a)
    }
  }

  /** Generic jsoniter codec for `Tristate[A, S]`, derived from any
    * underlying `JsonValueCodec[A]`.
    *
    * On decode it peeks for a JSON `null` literal; if found, returns
    * [[Tristate.Null]]. Otherwise the inner codec decodes the value
    * into a [[Tristate.Value]]. The [[Tristate.Absent]] state is
    * never produced here — it is reached exclusively via the field's
    * case-class default value when the key is missing from the JSON
    * object.
    *
    * On encode, [[Tristate.Absent]] writes nothing; the macro-
    * generated field writer skips it because the case class default
    * equals the encoded value (`transientDefault = true`, which is
    * the jsoniter default). [[Tristate.Null]] writes a JSON null;
    * [[Tristate.Value]] delegates to the inner codec.
    *
    * The `S` type parameter is phantom — the codec ignores it at
    * runtime. If the wire format produces a state that's not in `S`
    * (e.g. a `null` arrives for an `Optional[A]` field), the codec
    * still constructs the corresponding `Tristate` case; the cast is
    * a soundness gap accepted at the network boundary. The
    * type-system precision is enforced on the construction side,
    * which is where bugs are caught at compile time.
    *
    * `nullValue` returns [[Tristate.Null]], used by jsoniter as a
    * sentinel when this codec is itself a fallback target.
    */
  given codec[A, S](using inner: JsonValueCodec[A]): JsonValueCodec[Tristate[A, S]] =
    new JsonValueCodec[Tristate[A, S]] {
      def decodeValue(in: JsonReader, default: Tristate[A, S]): Tristate[A, S] = {
        if (in.isNextToken('n')) {
          in.readNullOrError(Tristate.Null.asInstanceOf[Tristate[A, S]], "expected null")
        } else {
          in.rollbackToken()
          // Cast: `Tristate.Value[A]: Tristate[A, States.Value]`, but
          // we need `Tristate[A, S]` for some arbitrary phantom `S`.
          // Soundness is enforced at the construction site; here we
          // trust the wire format to match the field's declared S.
          Tristate.Value(inner.decodeValue(in, null.asInstanceOf[A])).asInstanceOf[Tristate[A, S]]
        }
      }

      def encodeValue(x: Tristate[A, S], out: JsonWriter): Unit = x match {
        case Tristate.Null => out.writeNull()
        case Tristate.Value(a) => inner.encodeValue(a, out)
        case Tristate.Absent =>
          out.encodeError(
            "Tristate.Absent must not reach encodeValue; ensure transientDefault=true and Absent is the field default"
          )
      }

      def nullValue: Tristate[A, S] = Tristate.Null.asInstanceOf[Tristate[A, S]]
    }
}
