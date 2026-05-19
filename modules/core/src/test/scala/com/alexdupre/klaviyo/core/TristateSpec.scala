package com.alexdupre.klaviyo.core

import com.alexdupre.klaviyo.core.Codecs.given
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/** Exercises the three-state field model end-to-end.
  *
  * The point of [[Tristate]] is to keep `value` / `null` / `absent`
  * distinguishable on the wire. These tests assert exactly that
  * round-trip behaviour through a small `TestPayload` case class that
  * carries one field of each underlying type.
  */
final class TristateSpec extends munit.FunSuite {

  /** Test payload: one field of each likely type so we exercise the
    * codec for `Tristate[String]`, `Tristate[Int]`, `Tristate[Boolean]`
    * and `Tristate[Seq[String]]`. Each field defaults to `Absent` so
    * omitted JSON keys round-trip to `Absent`.
    */
  final case class TestPayload(
      s: Tristate.Maybe[String] = Tristate.Absent,
      i: Tristate.Maybe[Int] = Tristate.Absent,
      b: Tristate.Maybe[Boolean] = Tristate.Absent,
      xs: Tristate.Maybe[Vector[String]] = Tristate.Absent
  )

  object TestPayload {
    given JsonValueCodec[TestPayload] = JsonCodecMaker.make
  }

  test("absent fields are omitted on the wire") {
    val p = TestPayload()
    assertEquals(writeToString(p), "{}")
  }

  test("explicit null is written as JSON null") {
    val p = TestPayload(s = Tristate.Null, i = Tristate.Null)
    assertEquals(writeToString(p), """{"s":null,"i":null}""")
  }

  test("explicit value is written as the underlying JSON value") {
    val p = TestPayload(
      s = Tristate.Value("hello"),
      i = Tristate.Value(42),
      b = Tristate.Value(true),
      xs = Tristate.Value(Vector("a", "b"))
    )
    assertEquals(
      writeToString(p),
      """{"s":"hello","i":42,"b":true,"xs":["a","b"]}"""
    )
  }

  test("implicit conversion lets callers pass raw values") {
    // Note: only the `Value` direction is auto-converted. Users still
    // have to spell out Tristate.Null / Tristate.Absent explicitly.
    val p = TestPayload(s = "hello", i = 42, b = true)
    assertEquals(p.s, Tristate.Value("hello"))
    assertEquals(p.i, Tristate.Value(42))
    assertEquals(p.b, Tristate.Value(true))
  }

  test("absent / null / value all round-trip distinguishably") {
    val original = TestPayload(
      s = Tristate.Value("hello"),
      i = Tristate.Null,
      b = Tristate.Absent,
      xs = Tristate.Value(Vector("x"))
    )
    val json    = writeToString(original)
    val decoded = readFromString[TestPayload](json)

    // Absent field b survives the round-trip as Absent because the key
    // was not emitted, so the field default applies on decode.
    assertEquals(decoded.s, Tristate.Value("hello"))
    assertEquals(decoded.i, Tristate.Null)
    assertEquals(decoded.b, Tristate.Absent)
    assertEquals(decoded.xs, Tristate.Value(Vector("x")))
  }

  test("decoded null is Tristate.Null, not Absent") {
    val decoded = readFromString[TestPayload]("""{"s":null}""")
    assertEquals(decoded.s, Tristate.Null)
    // Other fields not present → Absent.
    assertEquals(decoded.i, Tristate.Absent)
  }

  test("decoded missing key is Absent, not Null") {
    val decoded = readFromString[TestPayload]("{}")
    assertEquals(decoded.s, Tristate.Absent)
    assertEquals(decoded.i, Tristate.Absent)
    assertEquals(decoded.b, Tristate.Absent)
    assertEquals(decoded.xs, Tristate.Absent)
  }

  test("predicate helpers behave consistently") {
    assert(Tristate.Value(1).isValue && Tristate.Value(1).isPresent && !Tristate.Value(1).isNull)
    assert(Tristate.Null.isPresent && Tristate.Null.isNull && !Tristate.Null.isValue)
    assert(Tristate.Absent.isAbsent && !Tristate.Absent.isPresent)
  }

  test("toOption collapses null and absent to None") {
    assertEquals(Tristate.Value("x").toOption, Some("x"))
    assertEquals(Tristate.Null.toOption, None)
    assertEquals(Tristate.Absent.toOption, None)
  }

  test("fold visits exactly one branch per state") {
    def label(t: Tristate.Maybe[Int]): String = t.fold("absent", "null", n => s"value=$n")
    assertEquals(label(Tristate.Absent), "absent")
    assertEquals(label(Tristate.Null), "null")
    assertEquals(label(Tristate.Value(7)), "value=7")
  }

  test("get returns the wrapped value or throws with a state-specific message") {
    assertEquals(Tristate.Value(42).get, 42)
    val absentErr = intercept[NoSuchElementException](Tristate.Absent.asInstanceOf[Tristate.Maybe[Int]].get)
    assert(absentErr.getMessage.contains("Absent"))
    val nullErr = intercept[NoSuchElementException](Tristate.Null.asInstanceOf[Tristate.Maybe[Int]].get)
    assert(nullErr.getMessage.contains("Null"))
  }

  test("getOrElse returns the value or the fallback, with by-name fallback") {
    assertEquals(Tristate.Value("hi").getOrElse("fallback"), "hi")
    assertEquals(Tristate.Absent.asInstanceOf[Tristate.Maybe[String]].getOrElse("fallback"), "fallback")
    assertEquals(Tristate.Null.asInstanceOf[Tristate.Maybe[String]].getOrElse("fallback"), "fallback")
    // Fallback is by-name — not evaluated when not needed.
    var evaluated = false
    val _ = Tristate.Value("hi").getOrElse { evaluated = true; "never" }
    assert(!evaluated, "by-name fallback should not be evaluated when value is present")
  }

  test("contains checks equality with the wrapped value, false on absent/null") {
    assert(Tristate.Value(42).contains(42))
    assert(!Tristate.Value(42).contains(7))
    assert(!Tristate.Absent.asInstanceOf[Tristate.Maybe[Int]].contains(42))
    assert(!Tristate.Null.asInstanceOf[Tristate.Maybe[Int]].contains(42))
  }

  test("exists tests the predicate against the wrapped value, false on absent/null") {
    assert(Tristate.Value(42).exists(_ > 0))
    assert(!Tristate.Value(42).exists(_ < 0))
    assert(!Tristate.Absent.asInstanceOf[Tristate.Maybe[Int]].exists(_ => true))
    assert(!Tristate.Null.asInstanceOf[Tristate.Maybe[Int]].exists(_ => true))
  }

  test("foreach runs the side effect only when Value, never on absent/null") {
    var calls = 0
    Tristate.Value("hi").foreach { _ => calls += 1 }
    Tristate.Absent.asInstanceOf[Tristate.Maybe[String]].foreach { _ => calls += 1 }
    Tristate.Null.asInstanceOf[Tristate.Maybe[String]].foreach { _ => calls += 1 }
    assertEquals(calls, 1)
  }

  test("flatMap delegates on Value, keeps Absent / Null otherwise") {
    val f: Int => Tristate.Maybe[String] = n => if (n > 0) Tristate.Value(s"+$n") else Tristate.Null
    assertEquals(Tristate.Value(7).flatMap(f), Tristate.Value("+7"))
    assertEquals(Tristate.Value(-1).flatMap(f), Tristate.Null)
    assertEquals(Tristate.Absent.asInstanceOf[Tristate.Maybe[Int]].flatMap(f), Tristate.Absent)
    assertEquals(Tristate.Null.asInstanceOf[Tristate.Maybe[Int]].flatMap(f), Tristate.Null)
  }

  test("flatMap accepts functions returning any concrete state without explicit widening") {
    // The function returns `Optional[String]` (no Null case), but
    // `Tristate.flatMap` accepts it because `Optional[B] <: Maybe[B]`
    // via the phantom-state covariance. The result is `Maybe[String]`
    // — wider than either input, matching how `map` widens.
    val toOptional: Int => Tristate.Optional[String] = n => if (n > 0) Tristate.Value(s"+$n") else Tristate.Absent
    val r: Tristate.Maybe[String] = Tristate.Value(7).flatMap(toOptional)
    assertEquals(r, Tristate.Value("+7"))
  }

  test("collect applies the partial function when defined, otherwise collapses Value to Absent") {
    val pf: PartialFunction[Int, String] = { case n if n > 0 => s"+$n" }
    // Value where PF is defined → mapped Value.
    assertEquals(Tristate.Value(7).collect(pf), Tristate.Value("+7"))
    // Value where PF is NOT defined → Absent (the "filtered out" state).
    assertEquals(Tristate.Value(-1).collect(pf), Tristate.Absent)
    // Absent / Null receivers pass through.
    assertEquals(Tristate.Absent.asInstanceOf[Tristate.Maybe[Int]].collect(pf), Tristate.Absent)
    assertEquals(Tristate.Null.asInstanceOf[Tristate.Maybe[Int]].collect(pf), Tristate.Null)
  }

  test("filter keeps Value when predicate holds, otherwise Absent; preserves Null") {
    assertEquals(Tristate.Value(7).filter(_ > 0), Tristate.Value(7))
    assertEquals(Tristate.Value(-1).filter(_ > 0), Tristate.Absent)
    assertEquals(Tristate.Absent.asInstanceOf[Tristate.Maybe[Int]].filter(_ > 0), Tristate.Absent)
    assertEquals(Tristate.Null.asInstanceOf[Tristate.Maybe[Int]].filter(_ > 0), Tristate.Null)
  }

  test("filterNot is the complement of filter") {
    assertEquals(Tristate.Value(7).filterNot(_ < 0), Tristate.Value(7))
    assertEquals(Tristate.Value(-1).filterNot(_ < 0), Tristate.Absent)
    assertEquals(Tristate.Null.asInstanceOf[Tristate.Maybe[Int]].filterNot(_ < 0), Tristate.Null)
  }

  test("for-comprehension `if` guard goes through withFilter and prunes Value") {
    val r =
      for {
        n <- Tristate.Value(7): Tristate.Maybe[Int]
        if n > 0
      } yield n + 1
    assertEquals(r, Tristate.Value(8))

    val pruned =
      for {
        n <- Tristate.Value(-1): Tristate.Maybe[Int]
        if n > 0
      } yield n + 1
    assertEquals(pruned, Tristate.Absent)
  }

  test("orElse returns this on Value, otherwise the alternative; by-name") {
    assertEquals(Tristate.Value("hi").orElse(Tristate.Value("fallback")), Tristate.Value("hi"))
    assertEquals(
      Tristate.Absent.asInstanceOf[Tristate.Maybe[String]].orElse(Tristate.Value("fallback")),
      Tristate.Value("fallback")
    )
    assertEquals(
      Tristate.Null.asInstanceOf[Tristate.Maybe[String]].orElse(Tristate.Value("fallback")),
      Tristate.Value("fallback")
    )
    var evaluated = false
    val _ = Tristate.Value("hi").orElse { evaluated = true; Tristate.Value("never") }
    assert(!evaluated, "alternative should not be evaluated when the receiver is Value")
  }

  test("flatten on a nested Tristate keeps the inner state, propagates Absent/Null") {
    val nestedValue: Tristate.Maybe[Tristate.Maybe[Int]] = Tristate.Value(Tristate.Value(7))
    assertEquals(nestedValue.flatten, Tristate.Value(7))

    val nestedNullInner: Tristate.Maybe[Tristate.Maybe[Int]] = Tristate.Value(Tristate.Null)
    assertEquals(nestedNullInner.flatten, Tristate.Null)

    val nestedAbsentInner: Tristate.Maybe[Tristate.Maybe[Int]] = Tristate.Value(Tristate.Absent)
    assertEquals(nestedAbsentInner.flatten, Tristate.Absent)

    val outerAbsent: Tristate.Maybe[Tristate.Maybe[Int]] = Tristate.Absent
    assertEquals(outerAbsent.flatten, Tristate.Absent)

    val outerNull: Tristate.Maybe[Tristate.Maybe[Int]] = Tristate.Null
    assertEquals(outerNull.flatten, Tristate.Null)
  }

  test("iterator / toList / toVector yield one element on Value, empty otherwise") {
    assertEquals(Tristate.Value(7).iterator.toList, List(7))
    assertEquals(Tristate.Absent.asInstanceOf[Tristate.Maybe[Int]].iterator.toList, Nil)
    assertEquals(Tristate.Null.asInstanceOf[Tristate.Maybe[Int]].iterator.toList, Nil)

    assertEquals(Tristate.Value(7).toList, List(7))
    assertEquals(Tristate.Absent.asInstanceOf[Tristate.Maybe[Int]].toList, Nil)

    assertEquals(Tristate.Value(7).toVector, Vector(7))
    assertEquals(Tristate.Null.asInstanceOf[Tristate.Maybe[Int]].toVector, Vector.empty[Int])
  }

  test("nonEmpty is a synonym for isValue") {
    assert(Tristate.Value(7).nonEmpty)
    assert(!Tristate.Absent.asInstanceOf[Tristate.Maybe[Int]].nonEmpty)
    assert(!Tristate.Null.asInstanceOf[Tristate.Maybe[Int]].nonEmpty)
  }
}
