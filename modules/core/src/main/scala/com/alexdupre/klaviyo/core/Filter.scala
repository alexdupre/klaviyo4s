package com.alexdupre.klaviyo.core

/** A Klaviyo filter expression, ready to land in the `filter=` query
  * parameter on a paginated endpoint.
  *
  * Klaviyo's filter syntax is `operator(field,value)` for single-value
  * operators and `operator(field,v1,v2,...)` for list-form operators.
  * Multiple expressions are AND-combined by joining with commas at
  * the same `filter=` parameter:
  *
  * {{{
  * filter=equals(status,'Sent'),greater-or-equal(created_at,'2024-01-01')
  * }}}
  *
  * Users do not construct `Filter` directly — the code generator
  * emits a per-endpoint typed builder (e.g. `GetCampaignsFilter`)
  * whose method calls produce `Filter` values. The opaque type keeps
  * the wire format encapsulated and prevents the user from
  * accidentally interpolating malformed strings.
  *
  * @see [[Filter.and]] / [[Filter.all]] for composition
  */
opaque type Filter = String

object Filter {

  /** Wrap a pre-built filter expression. Used by the code generator
    * (and by power users who want to escape the typed DSL); plain
    * application code should normally reach for the per-endpoint
    * builder instead.
    */
  inline def apply(wire: String): Filter = wire

  extension (f: Filter) {

    /** Render to the wire-format string. The code generator calls
      * this when serialising the `filter=` query parameter.
      */
    inline def render: String = f

    /** AND-compose two filters. Klaviyo joins multiple filter
      * expressions at the same `filter=` parameter with commas — no
      * separate `or` operator is supported at the spec level today,
      * so the only legal composition is conjunction.
      */
    infix def and(other: Filter): Filter = s"$f,$other"
  }

  /** AND-combine a non-empty list of filters. Convenience wrapper
    * over repeated [[and]] application that fails loudly on empty
    * input rather than producing a no-op filter (which Klaviyo
    * would reject as malformed).
    */
  def all(first: Filter, rest: Filter*): Filter =
    rest.foldLeft(first)((acc, f) => acc.and(f))

  // --- Value formatting helpers used by the generated builders ---
  //
  // The generated `<Op>Filter` code calls these to render typed
  // Scala values into the operator's wire payload. Centralised here
  // so a future change (e.g. a Klaviyo escape rule) is one edit.

  /** Render a string value as `"escaped"`. Klaviyo's filter grammar
    * requires double-quoted strings (single quotes are NOT accepted
    * at the filter-syntax level, even though earlier versions of
    * Klaviyo's docs implied otherwise). Embedded double quotes and
    * backslashes are backslash-escaped to keep the literal a single
    * token.
    *
    * The double quotes themselves are not URI-safe — sttp's URI
    * builder encodes them to `%22` when this filter value lands in
    * the `filter=` query parameter. Spaces in the value become
    * `%20`. The filter syntax (parens, commas) stays unencoded
    * because those characters are allowed in query values per
    * RFC 3986.
    */
  def quoteString(s: String): String = {
    val sb = new java.lang.StringBuilder(s.length + 2)
    sb.append('"')
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (c == '\\' || c == '"') sb.append('\\')
      sb.append(c)
      i += 1
    }
    sb.append('"')
    sb.toString
  }

  /** Render a boolean as a bare `true` / `false` (no quotes).
    * Klaviyo's filter language accepts both quoted and unquoted
    * booleans; the unquoted form is what their own examples use.
    */
  inline def renderBoolean(b: Boolean): String = if (b) "true" else "false"

  /** Render a numeric value as its plain decimal form, no quotes. */
  inline def renderNumber(n: Long): String = n.toString
  inline def renderNumberD(n: Double): String = n.toString
}
