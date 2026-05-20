package com.alexdupre.klaviyo.codegen.model

import com.alexdupre.klaviyo.codegen.spec.Parser

import java.nio.file.Paths

/** Tests the planner end-to-end against the real `specs/stable.json`.
  *
  * The whole-spec plan is parsed once per class via a lazy val and
  * shared across tests; subsequent test methods reuse the cached
  * value. We then narrow assertions to the parts of the plan we
  * care about (e.g. the `Accounts` category, the `AccountEnum`
  * type) using the helpers below.
  */
final class PlannerSpec extends munit.FunSuite {

  private lazy val plan: SpecPlan = {
    val raw = Parser.parseFile(Paths.get("specs/stable.json"))
    Planner.plan(raw)
  }

  private def category(name: String): CategoryPlan =
    plan.categories.find(_.className == name).getOrElse(fail(s"no category $name"))

  test("plan revision matches the spec's info.version") {
    assert(plan.revision.matches("""\d{4}-\d{2}-\d{2}"""), s"unexpected revision: ${plan.revision}")
  }

  test("all 23 Klaviyo categories appear in the plan, derived from tags") {
    val packageNames = plan.categories.map(_.packageName).toSet
    val expected = Set(
      "accounts", "campaigns", "catalogs", "client", "conversations", "coupons",
      "customobjects", "dataprivacy", "events", "flows", "forms", "images",
      "lists", "metrics", "profiles", "reporting", "reviews", "segments",
      "tags", "templates", "trackingsettings", "webfeeds", "webhooks"
    )
    val missing = expected.diff(packageNames)
    assert(missing.isEmpty, s"missing categories: $missing")
    assertEquals(plan.categories.size, 23)
  }

  test("AccountEnum is dropped after the Wire-shim discriminator sweep") {
    // `AccountEnum` was a single-value string enum used solely as the
    // JSON:API `type` discriminator on `AccountResponseObjectResource`.
    // The planner now hides that discriminator behind a Wire-shim
    // field rather than exposing it as a `Ref(AccountEnum)`, which
    // leaves `AccountEnum` orphaned. The reachability sweep drops it
    // from the emitted tree. The exact same logic applies to every
    // other JSON:API `<Resource>Enum` in the spec.
    assert(plan.types.forall(_.name != "AccountEnum"), "AccountEnum should be absent from the plan")
  }

  test("AccountResponseObjectResource is emitted as a Record") {
    val td = plan.types.find(_.name == "AccountResponseObjectResource").getOrElse(fail("missing"))
    td match {
      case r: TypeDef.Record =>
        val names = r.fields.map(_.scalaName)
        // The JSON:API `type` discriminator is hidden — it never
        // appears on the public case class, only on the synthetic
        // Wire shim the codec uses behind the scenes.
        assertEquals(names, List("id", "attributes", "links"))
        assertEquals(r.hiddenDiscriminators.map(_.jsonName), List("type"))
        assertEquals(r.hiddenDiscriminators.map(_.constantValue), List("account"))
      case other             => fail(s"expected Record, got $other")
    }
  }

  test("inline `attributes` object is extracted as a synthetic record") {
    val synthName = "AccountResponseObjectResourceAttributes"
    val td        = plan.types.find(_.name == synthName).getOrElse(fail(s"missing synthetic type $synthName"))
    td match {
      case r: TypeDef.Record =>
        val names = r.fields.map(_.scalaName)
        assertEquals(
          names,
          List("testAccount", "contactInformation", "industry", "timezone", "preferredCurrency", "publicApiKey", "locale")
        )
      case other             => fail(s"expected synthetic Record, got $other")
    }
  }

  test("shared schema ObjectLinks is rewritten to the core ResourceLinks ref, not emitted") {
    assert(!plan.types.exists(_.name == "ObjectLinks"), "ObjectLinks should be deduplicated against klaviyo4s-core")
    val resource = plan.types
      .collectFirst { case r: TypeDef.Record if r.name == "AccountResponseObjectResource" => r }
      .getOrElse(fail("missing AccountResponseObjectResource"))
    val linksTpe = resource.fields.find(_.scalaName == "links").map(_.scalaType)
    assertEquals(
      linksTpe,
      Some(ScalaType.Ref("com.alexdupre.klaviyo.core.jsonapi.ResourceLinks"))
    )
  }

  test("oneOf with colliding primary discriminators tie-breaks via a secondary single-value-enum field") {
    // FlowDefinition.triggers is a `oneOf` of seven trigger schemas
    // discriminated by `type`. Two of them — ProfilePropertyDateTrigger
    // and CustomObjectDateTrigger — share `type: "date"`. The planner
    // detects that `date_field_type` is a single-value enum on both
    // (`"profile-property"` vs `"custom-object"`) and uses it as a
    // secondary tie-breaker. Result: all seven variants stay proper
    // refs; no merged record needed.
    val triggers = plan.types
      .collectFirst { case u: TypeDef.ResourceUnion if u.name == "FlowDefinitionTriggers" => u }
      .getOrElse(fail("expected FlowDefinitionTriggers to be a ResourceUnion"))

    assertEquals(triggers.variants.size, 7, "tie-break should keep all seven trigger variants")

    val primaryDiscValues = triggers.variants.map(_.discriminator.head._2).distinct
    assertEquals(
      primaryDiscValues.toSet,
      Set("list", "segment", "metric", "date", "price-drop", "low-inventory")
    )

    // Five variants have a single-field discriminator (just `type`);
    // the two date variants have a two-field composite discriminator.
    val singleField = triggers.variants.filter(_.discriminator.size == 1)
    val multiField  = triggers.variants.filter(_.discriminator.size == 2)
    assertEquals(singleField.size, 5)
    assertEquals(multiField.size, 2)

    val dateVariants = multiField
    assert(dateVariants.forall(_.discriminator.head == ("type", "date")))
    assertEquals(
      dateVariants.map(_.discriminator(1)).toSet,
      Set("date_field_type" -> "profile-property", "date_field_type" -> "custom-object")
    )

    // Each date variant points DIRECTLY at the original schema, not at
    // a merged synthetic record — that's the whole win of multi-field
    // discrimination.
    val dateTargets = dateVariants.collect { case ResourceVariant(_, _, ScalaType.Ref(n)) => n }.toSet
    assertEquals(dateTargets, Set("ProfilePropertyDateTrigger", "CustomObjectDateTrigger"))
    // No `AnyDateTrigger` merged record should have been synthesised.
    assert(!plan.types.exists(_.name == "AnyDateTrigger"),
      "tie-break replaces the merge, so AnyDateTrigger should not be in the plan")
  }

  test("multi-field tie-break works on filter-style unions (numeric + operator)") {
    // ProfilePostalCodeDistanceCondition.filter has two variants that
    // both have `type: "numeric"`. The secondary `operator` field is
    // a single-value enum (`"greater-than"` vs `"less-than"`) and ties
    // them apart cleanly. After the option-2 fall-through is itself
    // shadowed by multi-field detection, we now get a proper 2-arm
    // sealed trait.
    val cond = plan.types
      .collectFirst { case u: TypeDef.ResourceUnion if u.name == "ProfilePostalCodeDistanceConditionFilter" => u }
      .getOrElse(fail("expected ProfilePostalCodeDistanceConditionFilter to be a ResourceUnion"))

    assertEquals(cond.variants.size, 2)
    cond.variants.foreach { v =>
      assertEquals(v.discriminator.size, 2)
      assertEquals(v.discriminator.head, ("type", "numeric"))
      assert(v.discriminator(1)._1 == "operator")
    }
    assertEquals(
      cond.variants.map(_.discriminator(1)._2).toSet,
      Set("greater-than", "less-than")
    )
  }

  test("Compound document ResourceUnions keep one variant per included resource type") {
    // The compound-document `included` unions are the original
    // non-colliding use case for ResourceUnion. Pick one whose
    // discriminators are guaranteed to be all distinct (each maps
    // to a single JSON:API resource type) and verify the hybrid
    // grouping logic is a no-op there — same number of variants
    // as discriminators, each variant Ref'd at a distinct target.
    val compoundUnions = plan.types.collect {
      case u: TypeDef.ResourceUnion if u.name.endsWith("CompoundDocumentIncluded") => u
    }
    assert(compoundUnions.nonEmpty, "expected at least one CompoundDocumentIncluded ResourceUnion")
    compoundUnions.foreach { u =>
      val targetNames = u.variants.collect { case ResourceVariant(_, _, ScalaType.Ref(n)) => n }
      assertEquals(
        targetNames.distinct.size,
        u.variants.size,
        s"${u.name}: each variant should point at a distinct target ref"
      )
      // Each variant's primary discriminator is unique (no ambiguity
      // → no tie-breaker needed → length-1 discriminator).
      assertEquals(
        u.variants.map(_.discriminator).distinct.size,
        u.variants.size,
        s"${u.name}: discriminators must be unique"
      )
      u.variants.foreach { v =>
        assertEquals(v.discriminator.size, 1, s"${u.name}.${v.caseName}: expected single-field discriminator")
      }
    }
  }

  test("CollectionLinks is also deduplicated against core") {
    assert(!plan.types.exists(_.name == "CollectionLinks"))
  }

  test("Accounts category: get_accounts operation name is camelCased") {
    val op = category("Accounts").operations
      .find(_.scalaName == "getAccounts")
      .getOrElse(fail("missing operation"))
    assertEquals(op.httpMethod, "GET")
    assertEquals(op.pathTemplate, "/api/accounts")
  }

  test("bracket-style parameter name becomes camelCase, original wire name is preserved") {
    val op       = category("Accounts").operations.find(_.scalaName == "getAccounts").get
    val fieldsP  = op.parameters
      .find(_.jsonName == "fields[account]")
      .getOrElse(fail(s"missing fields[account] in ${op.parameters.map(_.jsonName)}"))
    assertEquals(fieldsP.scalaName, "fieldsAccount")
    assertEquals(fieldsP.location, ParamLocation.Query)
    assertEquals(fieldsP.required, false)
  }

  test("revision header parameter is detected and tagged as a header") {
    val op   = category("Accounts").operations.find(_.scalaName == "getAccounts").get
    val rev  = op.parameters.find(_.jsonName == "revision").getOrElse(fail("missing revision param"))
    assertEquals(rev.location, ParamLocation.Header)
    assertEquals(rev.required, true)
  }

  test("path-templated id parameter is detected and tagged as a path param") {
    val op = category("Accounts").operations.find(_.scalaName == "getAccount").get
    val id = op.parameters.find(_.jsonName == "id").getOrElse(fail("missing id"))
    assertEquals(id.location, ParamLocation.Path)
    assertEquals(id.required, true)
  }

  test("success response type points at the generated envelope record") {
    val op = category("Accounts").operations.find(_.scalaName == "getAccount").get
    op.successType match {
      case ScalaType.Ref("GetAccountResponse") => () // good
      case other                               => fail(s"expected Ref(GetAccountResponse), got $other")
    }
  }
}

/** Verifies the tag-to-name derivation for multi-word categories.
  *
  * Klaviyo's `stable.json` tags include `"Data Privacy"`, `"Custom
  * Objects"`, etc. The planner should map those to package names
  * `dataprivacy`, `customobjects`, ... and PascalCased class names
  * `DataPrivacy`, `CustomObjects`, ...
  */
final class CategoryNameSpec extends munit.FunSuite {

  private lazy val plan: SpecPlan = {
    val raw = Parser.parseFile(Paths.get("specs/stable.json"))
    Planner.plan(raw)
  }

  test("'Data Privacy' tag → packageName 'dataprivacy', className 'DataPrivacy'") {
    val cat = plan.categories
      .find(_.className == "DataPrivacy")
      .getOrElse(fail(s"no DataPrivacy in ${plan.categories.map(_.className)}"))
    assertEquals(cat.packageName, "dataprivacy")
  }

  test("multi-word tags strip spaces correctly for every spec-driven category") {
    // Spot-check the rest of the multi-word categories in case a
    // refresh introduces a new naming quirk.
    def find(name: String): CategoryPlan =
      plan.categories.find(_.className == name).getOrElse(fail(s"no $name"))
    assertEquals(find("CustomObjects").packageName, "customobjects")
    assertEquals(find("WebFeeds").packageName, "webfeeds")
    assertEquals(find("TrackingSettings").packageName, "trackingsettings")
  }

  test("tag-to-name helpers work on the planner's internal helpers without a real spec") {
    val planner = new Planner(
      com.alexdupre.klaviyo.codegen.spec.RawSpec(
        openapi = "3.0.2",
        info = com.alexdupre.klaviyo.codegen.spec.RawInfo("t", "v")
      )
    )
    assertEquals(planner.tagToPackageName("Custom Objects"), "customobjects")
    assertEquals(planner.tagToClassName("Custom Objects"), "CustomObjects")
    assertEquals(planner.tagToPackageName("Web Feeds"), "webfeeds")
    assertEquals(planner.tagToClassName("Web Feeds"), "WebFeeds")
  }
}

/** Pure unit tests for the small naming helpers — they have no need
  * for a spec at all.
  */
final class NamingSpec extends munit.FunSuite {

  private val helper = new Planner(
    com.alexdupre.klaviyo.codegen.spec.RawSpec(
      openapi = "3.0.2",
      info = com.alexdupre.klaviyo.codegen.spec.RawInfo("t", "v")
    )
  )

  test("snake_case becomes camelCase") {
    assertEquals(helper.toCamelCase("test_account"), "testAccount")
    assertEquals(helper.toCamelCase("public_api_key"), "publicApiKey")
    assertEquals(helper.toCamelCase("locale"), "locale")
  }

  test("bracket params are stripped and joined") {
    assertEquals(helper.toScalaIdentifier("fields[account]"), "fieldsAccount")
    assertEquals(helper.toScalaIdentifier("page[cursor]"), "pageCursor")
    assertEquals(helper.toScalaIdentifier("page[size]"), "pageSize")
  }

  test("reserved-word identifiers come back backticked") {
    assertEquals(helper.toScalaIdentifier("type"), "`type`")
    assertEquals(helper.toScalaIdentifier("class"), "`class`")
    assertEquals(helper.toScalaIdentifier("enum"), "`enum`")
  }

  test("camelCase passthrough leaves already-cased names untouched") {
    assertEquals(helper.toCamelCase("alreadyCamel"), "alreadyCamel")
  }
}
