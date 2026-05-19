package com.alexdupre.klaviyo.core

/** Exercises the [[Filter]] wire-grammar helpers that the code
  * generator emits into every per-endpoint filter builder. These are
  * the only spots in the runtime where filter wire bytes are
  * constructed; if they regress, every generated filter breaks.
  */
final class FilterSpec extends munit.FunSuite {

  test("quoteString wraps in double quotes and escapes embedded quotes") {
    assertEquals(Filter.quoteString("hi"), "\"hi\"")
    assertEquals(Filter.quoteString("a\"b"), "\"a\\\"b\"")
    assertEquals(Filter.quoteString("\""), "\"\\\"\"")
  }

  test("quoteString escapes backslashes before quotes — order matters") {
    // Input: a single backslash. Expected wire: \\ (two backslashes
    // between the surrounding double quotes). If the backslash escape
    // ran AFTER the quote escape we'd double-escape any pre-existing
    // backslash sequences and the wire would diverge.
    assertEquals(Filter.quoteString("\\"), "\"\\\\\"")
    // Both: a backslash followed by a quote.
    assertEquals(Filter.quoteString("\\\""), "\"\\\\\\\"\"")
  }

  test("quoteString preserves spaces, parens, commas verbatim") {
    // These are URL-encoded later by sttp's URI builder; the filter
    // value itself should NOT pre-encode them.
    assertEquals(Filter.quoteString("a b"), "\"a b\"")
    assertEquals(Filter.quoteString("foo,bar"), "\"foo,bar\"")
    assertEquals(Filter.quoteString("(x)"), "\"(x)\"")
  }

  test("quoteString round-trips the empty string as a pair of quotes") {
    assertEquals(Filter.quoteString(""), "\"\"")
  }

  test("renderBoolean emits bare true/false with no quotes") {
    assertEquals(Filter.renderBoolean(true), "true")
    assertEquals(Filter.renderBoolean(false), "false")
  }

  test("renderNumber emits a plain decimal for Long") {
    assertEquals(Filter.renderNumber(0L), "0")
    assertEquals(Filter.renderNumber(-42L), "-42")
    assertEquals(Filter.renderNumber(Long.MaxValue), Long.MaxValue.toString)
  }

  test("renderNumberD emits a plain decimal for Double, no scientific notation for typical values") {
    assertEquals(Filter.renderNumberD(0.0), "0.0")
    assertEquals(Filter.renderNumberD(-1.5), "-1.5")
    assertEquals(Filter.renderNumberD(3.14), "3.14")
  }

  test("and joins two filters with a comma; render returns the wire form") {
    val a = Filter("equals(status,\"Sent\")")
    val b = Filter("greater-or-equal(created_at,\"2024-01-01\")")
    val joined = a.and(b)
    assertEquals(joined.render, """equals(status,"Sent"),greater-or-equal(created_at,"2024-01-01")""")
  }

  test("all left-folds the inputs with and; single arg is its own filter") {
    val a = Filter("equals(a,\"1\")")
    val b = Filter("equals(b,\"2\")")
    val c = Filter("equals(c,\"3\")")
    assertEquals(Filter.all(a, b, c).render, """equals(a,"1"),equals(b,"2"),equals(c,"3")""")
    assertEquals(Filter.all(a).render, """equals(a,"1")""")
  }
}
