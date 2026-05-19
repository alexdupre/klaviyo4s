package com.alexdupre.klaviyo.core

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/** Exercises the three construction paths on [[RawJson]] plus the
  * codec round-trip. RawJson is used everywhere Klaviyo's spec carries
  * an arbitrary-shape JSON value (template context, event properties,
  * webhook headers, …) — any regression here silently corrupts the
  * wire payload for every endpoint that touches one of those fields.
  */
final class RawJsonSpec extends munit.FunSuite {

  // Smallest case-class shell that carries a `RawJson` field, used in
  // the codec round-trip tests. Lifted to the class level so munit's
  // unused-local-definition linter doesn't flag the in-test `case class`.
  private case class Holder(payload: RawJson)
  private object Holder {
    given JsonValueCodec[Holder] = JsonCodecMaker.make
  }

  test("apply accepts every JSON value kind") {
    // Object, array, string, number, true, false, null all count as
    // "single well-formed JSON values" per jsoniter's `skip`.
    assertEquals(RawJson("""{"a":1}""").text, """{"a":1}""")
    assertEquals(RawJson("""[1, 2, 3]""").text, """[1, 2, 3]""")
    assertEquals(RawJson("""  "hi"  """).text, """  "hi"  """)
    assertEquals(RawJson("42").text, "42")
    assertEquals(RawJson("true").text, "true")
    assertEquals(RawJson("false").text, "false")
    assertEquals(RawJson("null").text, "null")
  }

  test("apply throws JsonReaderException on malformed input") {
    intercept[JsonReaderException](RawJson("{"))
    intercept[JsonReaderException](RawJson("not json"))
    intercept[JsonReaderException](RawJson(""))
  }

  test("parse returns Right for valid JSON, Left for malformed") {
    assertEquals(RawJson.parse("""{"x":true}""").map(_.text), Right("""{"x":true}"""))
    val err = RawJson.parse("{").left.toOption.getOrElse(fail("expected Left"))
    assert(err.nonEmpty, "error message should be populated")
  }

  test("unchecked stores whatever you hand it, no validation") {
    // Garbage in — no exception. The hazard is documented; the test
    // pins the behaviour so a future change that adds silent
    // validation here would surface as a test failure.
    assertEquals(RawJson.unchecked("not json at all").text, "not json at all")
  }

  test("codec preserves the wire bytes verbatim through a round-trip") {
    // Whitespace between tokens, key ordering, number formatting,
    // unicode escapes — all should survive. jsoniter reads the raw
    // bytes via `readRawValAsBytes` and writes them via `writeRawVal`,
    // so this is a verbatim copy.
    val original = """{"payload":{ "z":1 , "a" : [true, null,  2.5]  }}"""
    val decoded = readFromString[Holder](original)
    // Whitespace inside the inner object is preserved as-is.
    assertEquals(decoded.payload.text, """{ "z":1 , "a" : [true, null,  2.5]  }""")
    // Re-encode reproduces the original bytes.
    assertEquals(writeToString(decoded), original)
  }

  test("codec round-trips primitives and nulls as RawJson") {
    assertEquals(readFromString[Holder]("""{"payload":42}""").payload.text, "42")
    assertEquals(readFromString[Holder]("""{"payload":"hi"}""").payload.text, "\"hi\"")
    assertEquals(readFromString[Holder]("""{"payload":null}""").payload.text, "null")
  }
}
