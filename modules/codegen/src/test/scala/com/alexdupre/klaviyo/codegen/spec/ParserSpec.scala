package com.alexdupre.klaviyo.codegen.spec

import java.nio.file.Paths

/** Sanity check on the parser against the real, vendored
  * `specs/stable.json` — Klaviyo's combined OpenAPI document. If
  * the spec ever fails to parse cleanly we want to find out before
  * any downstream codegen runs against it.
  *
  * Parsing the full ~3 MB document is done once per test class via
  * a `lazy val`; subsequent test methods reuse the parsed value.
  */
final class ParserSpec extends munit.FunSuite {

  private lazy val spec: RawSpec =
    Parser.parseFile(Paths.get("specs/stable.json"))

  test("parses the real stable.json without error") {
    assertEquals(spec.openapi, "3.0.2")
    assertEquals(spec.info.title, "Klaviyo API")
    // The version is the spec revision — bumped on every Klaviyo
    // release; the assertion is loose so a spec refresh doesn't
    // break the test.
    assert(spec.info.version.matches("""\d{4}-\d{2}-\d{2}"""), s"unexpected version: ${spec.info.version}")
  }

  test("preserves path order from the spec") {
    val paths = spec.paths.keys.toList
    // First path declared in stable.json is `/api/accounts` — track
    // that exact opener as a regression sentinel against accidental
    // sort/shuffle in the parser.
    assertEquals(paths.headOption, Some("/api/accounts"))
  }

  test("captures operations including their operationId, tags, and parameters") {
    val getAccounts =
      spec.paths.getOrElse("/api/accounts", fail("missing /api/accounts")).get
        .getOrElse(fail("expected GET on /api/accounts"))
    assertEquals(getAccounts.operationId, "get_accounts")
    assertEquals(getAccounts.tags, List("Accounts"))
    // The `fields[account]` bracket-style name is the canonical
    // example of preserved wire-name verbatim.
    val paramNames = getAccounts.parameters.flatMap(_.name)
    assert(paramNames.contains("fields[account]"), s"missing bracket param: $paramNames")
    assert(paramNames.contains("revision"), s"missing revision header param: $paramNames")
  }

  test("captures component schemas") {
    val schemaNames = spec.components.schemas.keys.toSet
    // A handful of well-known schemas every spec carries; if a
    // refresh drops them, the test fails loudly.
    val required = Set("AccountEnum", "AccountResponseObjectResource", "GetAccountResponse", "ObjectLinks", "ErrorSource")
    val missing  = required.diff(schemaNames)
    assert(missing.isEmpty, s"missing schemas after parse: $missing")
  }

  test("preserves property order within a schema's `properties` map") {
    val resource    = spec.components.schemas("AccountResponseObjectResource")
    val attrsSchema = resource.properties("attributes")
    val attrOrder   = attrsSchema.properties.keys.toList
    val expected    = List(
      "test_account",
      "contact_information",
      "industry",
      "timezone",
      "preferred_currency",
      "public_api_key",
      "locale"
    )
    assertEquals(attrOrder, expected)
  }

  test("captures `$ref` strings verbatim") {
    val resource = spec.components.schemas("AccountResponseObjectResource")
    val typeRef  = resource.properties("type").ref
    assertEquals(typeRef, Some("#/components/schemas/AccountEnum"))
  }

  test("captures `nullable: true` flag") {
    val resource  = spec.components.schemas("AccountResponseObjectResource")
    val industry  = resource.properties("attributes").properties("industry")
    assertEquals(industry.nullable, Some(true))
  }

  test("captures `enum` lists for string enums") {
    val ae = spec.components.schemas("AccountEnum")
    assertEquals(ae.`type`, Some("string"))
    assertEquals(ae.enumValues.asStrings, List("account"))
  }
}
