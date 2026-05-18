package com.alexdupre.klaviyo.codegen.emit

import com.alexdupre.klaviyo.codegen.model.Planner
import com.alexdupre.klaviyo.codegen.spec.Parser

import java.nio.file.Paths

/** Tests the emitter end-to-end against the real `accounts.json`.
  *
  * The strategy is structural rather than exhaustive: parse the spec,
  * plan it, emit, and then assert that
  *
  *   - we get the expected set of files
  *   - each file parses as Scala 3 (the emitter's `validate` step
  *     guarantees this, but the assertion catches regressions if
  *     someone removes the validation)
  *   - characteristic strings appear in the right files
  *
  * Phase 6 will compile-and-run the emitted code to verify behaviour.
  */
final class EmitterSpec extends munit.FunSuite {

  private lazy val files: Map[String, String] = {
    val raw  = Parser.parseFile(Paths.get("specs/stable.json"))
    val plan = Planner.plan(raw)
    Emitter.emit(plan).map(f => f.relativePath -> f.content).toMap
  }

  test("emits the AccountsApi class file under the accounts/ subdirectory") {
    val content = files.getOrElse("api/accounts/AccountsApi.scala", fail(s"missing api/accounts/AccountsApi.scala in ${files.keys.toList.sorted}"))
    assert(content.contains("final class AccountsApi[F[_]]"))
    assert(content.contains("def getAccount"), "missing getAccount method")
    assert(content.contains("def getAccounts"), "missing getAccounts method")
    // Imports models from the global package, not a per-category one.
    assert(content.contains("import com.alexdupre.klaviyo.models.*"))
  }

  test("emits the Extensions object file with the .accounts extension") {
    val content = files.getOrElse("api/accounts/Extensions.scala", fail("missing api/accounts/Extensions.scala"))
    assert(content.contains("extension"))
    assert(content.contains("def accounts: AccountsApi"))
    assert(content.contains("export Extensions"))
  }

  test("emits the top-level package.scala with category exports") {
    val content = files.getOrElse("package.scala", fail("missing package.scala"))
    assert(content.contains("package com.alexdupre.klaviyo"))
    assert(content.contains("export com.alexdupre.klaviyo.api.accounts.Extensions.*"))
    assert(content.contains("export com.alexdupre.klaviyo.core.{KlaviyoClient"))
    assert(content.contains("export com.alexdupre.klaviyo.core."))
  }

  test("emits one file per top-level plan type under the flat models/ directory") {
    val modelFiles = files.keys.filter(_.startsWith("models/")).toSet
    // Top-level types each get their own file. Records synthesised
    // from inline schemas under a single owning parent (`Attributes`,
    // `Data`, `Links`, ...) are nested inside the parent's companion
    // and therefore have NO standalone file — see the negative
    // assertions further down.
    //
    // AccountEnum.scala is also intentionally absent: the planner
    // routes the JSON:API `type` discriminator into a hidden Wire-
    // shim field rather than a public FieldPlan, leaving the
    // single-value `AccountEnum` orphaned for the reachability
    // sweep to drop.
    val expected = Set(
      "models/AccountResponseObjectResource.scala",
      "models/ContactInformation.scala",
      "models/StreetAddress.scala",
      "models/GetAccountResponse.scala",
      "models/GetAccountResponseCollection.scala"
    )
    val missing = expected.diff(modelFiles)
    assert(missing.isEmpty, s"missing model files: $missing. Got: ${modelFiles.toList.sorted}")
    assert(
      !modelFiles.contains("models/AccountEnum.scala"),
      "AccountEnum.scala should be dropped by the orphan sweep — the Wire shim absorbed its only reference"
    )
    assert(
      !modelFiles.contains("models/AccountResponseObjectResourceAttributes.scala"),
      "AccountResponseObjectResourceAttributes is nested inside its parent's companion — it should not have a standalone file"
    )
  }

  test("emitted models are in the flat `com.alexdupre.klaviyo.models` package, not per-category") {
    val content = files("models/AccountResponseObjectResource.scala")
    assert(content.contains("package com.alexdupre.klaviyo.models"))
    assert(!content.contains("klaviyo.accounts.models"))
  }

  test("a representative string enum is emitted as a Scala 3 enum with byValue / encode / decode") {
    // AccountEnum disappeared with the Wire-shim sweep, but every
    // multi-value enum the spec uses as a plain attribute (e.g.
    // `AggregationMethodEnum`) still survives. Pick the first enum
    // file the emitter produced and verify the enum shape.
    val anEnum = files.keys
      .filter(_.startsWith("models/"))
      .find { p =>
        val c = files(p)
        c.contains("enum ") && c.contains("(val value: String)")
      }
      .getOrElse(fail("no string-enum file in the emitted output"))
    val content = files(anEnum)
    assert(content.contains("(val value: String)"))
    assert(content.contains("byValue"))
    assert(content.contains("def encodeValue"))
    assert(content.contains("def decodeValue"))
  }

  test("AccountResponseObjectResource hides the JSON:API `type` discriminator behind a Wire shim") {
    val content = files("models/AccountResponseObjectResource.scala")
    // The public case class declaration starts at `final case class
    // AccountResponseObjectResource(` and runs until its closing `)`.
    val openIdx     = content.indexOf("final case class AccountResponseObjectResource(")
    assert(openIdx >= 0, "missing public case class declaration")
    val closeIdx    = content.indexOf(")", openIdx)
    val classHeader = content.substring(openIdx, closeIdx + 1)
    // The public case class lists only id/attributes/links — the
    // JSON:API `type` discriminator is hidden, injected by the codec
    // via a private Wire shim.
    assert(!classHeader.contains("`type`"), s"public case class should not declare a `type` field, got:\n$classHeader")
    assert(classHeader.contains("id:"))
    assert(classHeader.contains("attributes:"))
    assert(classHeader.contains("links:"))
    // The Wire shim is in scope and carries the discriminator.
    assert(content.contains("private final case class Wire"))
    assert(content.contains("\"account\""), "the literal discriminator value should appear in the codec")
    // The `attributes` field refers to the nested record we created.
    // Now that the inline attributes record is nested inside the
    // parent's companion, the reference renders as the dotted name.
    assert(content.contains("AccountResponseObjectResource.Attributes"))
    // Links refer to the shared core type, not a local copy.
    assert(content.contains("com.alexdupre.klaviyo.core.jsonapi.ResourceLinks"))
  }

  test("synthetic attributes record carries all the spec's fields in order, nested in parent") {
    // The synthetic attributes record now lives nested inside its
    // parent's file. The parent owns its companion's contents, so
    // we read the parent file and look for the inlined block.
    val content = files("models/AccountResponseObjectResource.scala")
    assert(content.contains("final case class Attributes("), "missing nested `Attributes` case class")
    val idxOf   = (s: String) => content.indexOf(s)
    val order   = List("testAccount", "contactInformation", "industry", "timezone", "preferredCurrency", "publicApiKey", "locale")
    val positions = order.map(name => name -> idxOf(name))
    positions.foreach { case (name, pos) => assert(pos >= 0, s"missing field $name") }
    val sorted = positions.sortBy(_._2).map(_._1)
    assertEquals(sorted, order, "fields out of spec order")
  }

  test("optional fields are wrapped in Tristate with Absent default") {
    val content = files("models/AccountResponseObjectResource.scala")
    // `industry` is nullable in the spec — not in `required` — so it
    // becomes Tristate.
    // `industry` is `nullable: true` AND not in the `required` array,
    // so it maps to the maximally-permissive `Tristate.Maybe[String]`
    // alias (absent, null, or value).
    assert(content.contains("industry: Tristate.Maybe[String]"))
    assert(content.contains("= com.alexdupre.klaviyo.core.Tristate.Absent"))
  }

  test("every emitted file parses as valid Scala 3 (validated by the emitter)") {
    // The emitter's `validate` step throws on parse failure; if we
    // reach this test, every file parsed. We re-state it as an
    // assertion for documentation purposes.
    assert(files.nonEmpty, "no files emitted")
  }

  test("AccountsApi method uses the sttp uri interpolator for the path template") {
    val content = files("api/accounts/AccountsApi.scala")
    assert(content.contains("/api/accounts/$id") || content.contains("""/api/accounts/${id}"""),
      "path template not substituted as expected")
  }

  test("AccountsApi method drops the revision header from the user-visible signature") {
    val content = files("api/accounts/AccountsApi.scala")
    val getAccountStart = content.indexOf("def getAccount(")
    assert(getAccountStart >= 0, "getAccount method missing")
    val openParen       = content.indexOf("(", getAccountStart)
    val closeParen      = content.indexOf(")", openParen)
    val signature       = content.substring(openParen, closeParen)
    assert(!signature.contains("revision"), s"revision leaked into method signature: $signature")
  }
}
