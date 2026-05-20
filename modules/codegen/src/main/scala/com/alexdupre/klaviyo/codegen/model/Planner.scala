package com.alexdupre.klaviyo.codegen.model

import com.alexdupre.klaviyo.codegen.spec.{EnumValues, RawDefault, RawOperation, RawParameter, RawSchema, RawSpec}

import scala.collection.immutable.ListMap
import scala.collection.mutable.ListBuffer

/** Converts a parsed [[com.alexdupre.klaviyo.codegen.spec.RawSpec]]
  * into the normalised [[CategoryPlan]] the emitter consumes.
  *
  * Responsibilities:
  *
  *   - Translate JSON-Schema constructs into [[ScalaType]] expressions
  *   - Synthesize named records for inline anonymous object schemas
  *     (Klaviyo's JSON:API resources put `attributes` inline)
  *   - Detect "well-known" shared schemas like `ObjectLinks` /
  *     `CollectionLinks` / `ErrorResponse` and rewrite their references
  *     to the equivalent types in `klaviyo4s-core` instead of emitting
  *     a copy per category
  *   - Normalise names: snake_case → camelCase, bracket-style names
  *     like `fields[account]` → `fieldsAccount`, reserved-keyword guard
  *
  * Not yet implemented (added on demand when first encountered in a
  * Klaviyo spec):
  *   - `allOf` composition flattening
  *   - `oneOf` of primitives → sealed union
  *   - `nullable: true` on `$ref` → `Option[T]`
  *
  * The implementation is a class so the in-progress synthesised types
  * can be collected as side-effects of the recursion. The public entry
  * point [[Planner.plan]] is pure.
  */
/** Drives off a single [[com.alexdupre.klaviyo.codegen.spec.RawSpec]] (Klaviyo's combined `stable.json`)
  * and produces a [[SpecPlan]] grouped by the operation `tags` field.
  *
  * Each operation in stable.json carries exactly one tag naming its
  * category (`"Accounts"`, `"Custom Objects"`, ...). The planner uses
  * the tag to derive the per-category package + class name; types
  * are flat and shared across the whole API.
  */
final class Planner(
  raw: RawSpec,
  selectedTags: Set[String] = Set.empty,
  selectedOperationIds: Set[String] = Set.empty
) {

  /** Map of well-known schema names to the qualified Scala name of the
    * equivalent type in `klaviyo4s-core`. When a category schema
    * matches a name in this map, the planner does not emit a copy —
    * it just rewrites refs to point at the shared core type.
    *
    * The mapping is by *name only*; we trust Klaviyo's spec to keep
    * the shape consistent across categories. If a future refresh ever
    * diverges, we'd see this surface as a compilation error in the
    * generated code (mismatched field types), which is the right place
    * to catch it.
    */
  private val sharedSchemaRefs: Map[String, String] = Map(
    "ObjectLinks" -> "com.alexdupre.klaviyo.core.jsonapi.ResourceLinks",
    "CollectionLinks" -> "com.alexdupre.klaviyo.core.jsonapi.CollectionLinks",
    "ErrorResponse" -> "com.alexdupre.klaviyo.core.jsonapi.ErrorDocument",
    "ErrorObject" -> "com.alexdupre.klaviyo.core.jsonapi.ErrorObject",
    "ErrorSource" -> "com.alexdupre.klaviyo.core.jsonapi.ErrorSource"
  )

  /** Accumulator for synthetic types created during schema traversal.
    * The buffer is appended to the final type list in plan order so
    * each synthetic record appears after its parent in the generated
    * source, which is what one would expect when scanning by hand.
    */
  private val synthesized = ListBuffer.empty[TypeDef]

  /** Memoises merged-oneOf record names keyed on the variant ref set.
    * Two parents that reference the same `oneOf: [A, B]` resolve to a
    * single shared type instead of synthesising structurally identical
    * duplicates with parent-prefixed names. The value is the final
    * (collision-disambiguated) Scala type name.
    */
  private val mergedOneOfCache = scala.collection.mutable.Map.empty[Set[String], String]

  /** Memoises synthesised inline string-enums keyed on their sorted
    * value set. Inline enums repeat heavily across Klaviyo's spec —
    * every endpoint declares its own `sort`, `fields[...]`, request-
    * method, content-type, etc. with the same set of values — so
    * caching by content collapses hundreds of structurally identical
    * synthetic types into one. The value is the final Scala type name.
    */
  private val inlineEnumByValues = scala.collection.mutable.Map.empty[List[String], String]

  private val inlineEnumContext = scala.collection.mutable.Map.empty[List[String], Planner.InlineEnumContext]

  /** Per-variant-set metadata for merged-oneOf records, populated as
    * the planner walks fields. The post-plan pass uses
    * `parents.size` to decide whether the synthetic record is
    * exclusive to one parent (nest it) or shared across many
    * (keep top-level).
    */
  private val mergedOneOfParents = scala.collection.mutable.Map.empty[Set[String], Planner.MergedOneOfContext]

  /** Run the planner and return an immutable [[SpecPlan]]. */
  def plan: SpecPlan = {
    val explicitTypes = raw.components.schemas.toList.flatMap { case (name, schema) =>
      if (sharedSchemaRefs.contains(name)) None
      else Some(toTypeDef(name, schema))
    }
    // Process each spec operation once. We keep the raw
    // `operationId` alongside the tag so the per-operation filter
    // below can match on it without re-walking the spec.
    val allTaggedOps = raw.paths.toList.flatMap { case (template, item) =>
      item.methods.map { case (method, op) =>
        val tag = op.tags match {
          case t :: Nil => t
          case Nil =>
            sys.error(s"Operation '${op.operationId}' ($method $template) has no tag — cannot assign a category.")
          case ts =>
            // Multi-tag operations don't appear in current Klaviyo
            // specs but the wire format allows them. Fail loudly so a
            // refresh that introduces ambiguity gets a deliberate
            // policy decision.
            sys.error(
              s"Operation '${op.operationId}' ($method $template) has ${ts.size} tags ${ts.mkString("[", ", ", "]")} — " +
                s"multi-category operations are not supported; pick one tag deterministically in Planner.plan."
            )
        }
        (tag, op.operationId, toOperation(method, template, op))
      }
    }
    // Operation selection — union of two independent filters:
    //
    //   - `selectedTags` matches the operation's category tag;
    //   - `selectedOperationIds` matches the raw `operationId` field
    //     from the spec.
    //
    // When BOTH are empty, every operation is kept (default
    // behaviour). When either is non-empty, an operation is kept iff
    // its tag is in `selectedTags` OR its `operationId` is in
    // `selectedOperationIds` — the two filters compose additively,
    // so a caller can pin a whole category via tags and add a few
    // extra ops from other categories by operationId. Names not
    // present in the spec are tolerated silently; callers wanting
    // strict matching diff the input against the spec themselves.
    val filterDisabled = selectedTags.isEmpty && selectedOperationIds.isEmpty
    val taggedOps =
      if (filterDisabled) allTaggedOps.map { case (t, _, op) => (t, op) }
      else allTaggedOps.collect {
        case (tag, opId, op) if selectedTags.contains(tag) || selectedOperationIds.contains(opId) =>
          (tag, op)
      }
    // Preserve first-seen tag order so the generated category list is
    // deterministic across runs (and tracks Klaviyo's natural API
    // ordering in stable.json).
    val tagOrder = taggedOps.iterator.map(_._1).toList.distinct
    val byTag = taggedOps.groupBy(_._1).map { case (k, v) => k -> v.map(_._2) }
    val categories = tagOrder.map { tag =>
      CategoryPlan(
        packageName = tagToPackageName(tag),
        className = tagToClassName(tag),
        operations = byTag(tag)
      )
    }
    val allTypes = explicitTypes ++ synthesized.result()
    // Walk the kept operations to collect referenced types, then
    // expand transitively through each type's internal refs. Only
    // types reachable from the surviving operations are kept;
    // everything else is dropped from the generated tree. This always
    // runs (not just under tag filtering) so that:
    //   - top-level enums orphaned by the Wire-shim discriminator
    //     drop (every JSON:API `<Resource>Enum` is gone after Planner
    //     routes the `type` field into `hiddenDiscriminators` instead
    //     of a FieldPlan that would `Ref` the enum);
    //   - tag-filtered runs prune the type tree to the reachable
    //     subset, which was the original motivation;
    //   - genuinely-unreferenced top-level components in the spec
    //     don't bloat the artefact.
    val seed = collectReferencedTypes(categories.flatMap(_.operations))
    val reachable = expandReachable(seed, allTypes)
    val keptTypes = allTypes.filter(td => reachable.contains(td.name))
    // Post-plan rename pass: every inline-synthesised string enum
    // was named eagerly with a first-claim-wins heuristic. Now that
    // we know which parents reference each value set, swap in
    // truthful names — bare `<Field>Enum` only for value sets with
    // no competitors, LCS-based names for everyone else. See
    // [[renameInlineEnums]] for the policy.
    val (renamedTypes, renamedCategories) = applyEnumRenames(keptTypes, categories)
    SpecPlan(
      revision = raw.info.version,
      types = renamedTypes,
      categories = renamedCategories
    )
  }

  /** Apply the post-plan rename pass to every [[ScalaType.Ref]] in
    * the plan: type field types, union variant types, operation
    * parameter types, and operation success types. StringEnum
    * `TypeDef`s themselves are rewritten in three ways:
    *
    *   - `name` is updated per [[renameInlineEnums]];
    *   - `parent` / `shortName` are set for single-parent inline
    *     enums so the emitter nests them in their parent's
    *     companion (matching how it already handles single-parent
    *     inline record children).
    *
    * Qualified refs (`com.alexdupre.…`) are passed through unchanged
    * — they target externally-defined types that the rename map
    * never names.
    */
  private def applyEnumRenames(
    types: List[TypeDef],
    categories: List[CategoryPlan]
  ): (List[TypeDef], List[CategoryPlan]) = {
    val renames = renameInlineEnums(types)
    // Build a `oldName -> (parent, shortName)` map for inline enums
    // that have exactly one parent AND whose parent is an actual
    // emitted Record in the plan. The plan-level name remains
    // whatever the rename pass produced (long, parent-prefixed) so
    // the global type table stays unique; `shortName` is the bare
    // `<Field>Enum` form, which is what the emitter uses for the
    // declaration inside the parent's companion. The display-name
    // map then resolves the long plan name to `Parent.FieldEnum`.
    //
    // Operation-parameter inline enums use a virtual parent name
    // like `<OpName>Param` that is *not* an emitted Record (it's
    // just a synthesis-prefix used during planning). Those enums
    // stay top-level — there's no parent companion to nest them in.
    val realRecordNames = types.iterator.collect { case r: TypeDef.Record => r.name }.toSet
    val inlineEnumNesting = inlineEnumContext.iterator.collect {
      case (valueSet, ctx) if ctx.parents.size == 1 && realRecordNames.contains(ctx.parents.head) =>
        val oldName = inlineEnumByValues(valueSet)
        val parent = ctx.parents.head
        oldName -> (parent, ctx.preferred)
    }.toMap

    // Same single-parent nesting rule for merged-oneOf records.
    // Multi-parent merged records (the LCS-named `AnyTimeframe`-style
    // ones) stay top-level because they're genuinely shared.
    val mergedOneOfNesting = mergedOneOfParents.iterator.collect {
      case (cacheKey, ctx) if ctx.parents.size == 1 && realRecordNames.contains(ctx.parents.head) =>
        val recordName = mergedOneOfCache(cacheKey)
        val parent = ctx.parents.head
        recordName -> (parent, s"${ctx.fieldPascal}")
    }.toMap

    def rewriteType(t: ScalaType): ScalaType = t match {
      case ScalaType.Ref(qn) if renames.contains(qn) => ScalaType.Ref(renames(qn))
      case ScalaType.App(c, args) => ScalaType.App(c, args.map(rewriteType))
      case other => other
    }
    def rewriteField(f: FieldPlan): FieldPlan = f.copy(scalaType = rewriteType(f.scalaType))
    def rewriteParam(p: ParamPlan): ParamPlan = p.copy(scalaType = rewriteType(p.scalaType))
    val newTypes = types.map {
      case e: TypeDef.StringEnum =>
        val newName = renames.getOrElse(e.name, e.name)
        val nestingForOld = inlineEnumNesting.get(e.name)
        e.copy(
          name = newName,
          parent = nestingForOld.map(_._1).orElse(e.parent),
          shortName = nestingForOld.map(_._2).orElse(e.shortName)
        )
      case r: TypeDef.Record =>
        val nestingForOld = mergedOneOfNesting.get(r.name)
        r.copy(
          name = r.name, // Records aren't renamed by this pass; their internal field refs are.
          fields = r.fields.map(rewriteField),
          parent = nestingForOld.map(_._1).orElse(r.parent),
          shortName = nestingForOld.map(_._2).orElse(r.shortName)
        )
      case u: TypeDef.PrimitiveUnion =>
        u.copy(variants = u.variants.map(v => v.copy(scalaType = rewriteType(v.scalaType))))
      case u: TypeDef.ResourceUnion =>
        u.copy(variants = u.variants.map(v => v.copy(scalaType = rewriteType(v.scalaType))))
    }
    val newCategories = categories.map { cat =>
      cat.copy(operations = cat.operations.map { op =>
        op.copy(
          parameters = op.parameters.map(rewriteParam),
          successType = rewriteType(op.successType)
        )
      })
    }
    (newTypes, newCategories)
  }

  /** Decide a "truthful" final name for every inline-synthesised
    * string enum, returning an `oldName → newName` map (entries
    * omitted when the name doesn't change).
    *
    * Policy:
    *
    *   - Group every recorded value set by its preferred name
    *     (`<Field>Enum`).
    *   - If exactly one value set wants that preferred name, it
    *     gets the bare `<Field>Enum` form.
    *   - If multiple value sets want it, *none* of them get the
    *     bare form. Each gets `<LCS-of-parents><Field>Enum` (LCS =
    *     longest common suffix of the requesting parent names),
    *     falling back to `<Parent><Field>Enum` when the LCS isn't
    *     meaningful (`< 3` chars or as long as every parent name).
    *
    * The bare short form is reserved for situations where it's
    * genuinely unique. Names with prefixes are guaranteed to
    * describe a real shared concept (the LCS over actual users)
    * rather than the arbitrary first-encountered parent.
    *
    * Names that collide with top-level component schemas or with
    * other already-claimed names are disambiguated via a numeric
    * suffix as a last resort.
    */
  private def renameInlineEnums(currentTypes: List[TypeDef]): Map[String, String] = {
    // Names already claimed in the plan (component schemas + any
    // non-inline-enum synthetic). Both are off-limits as targets.
    val claimed = scala.collection.mutable.Set.empty[String]
    currentTypes.foreach(t => if (!inlineEnumByValues.values.exists(_ == t.name)) claimed += t.name)

    val proposals = scala.collection.mutable.Map.empty[List[String], String]
    val grouped = inlineEnumContext.toList.groupBy { case (_, ctx) => ctx.preferred }
    grouped.foreach { case (preferred, entries) =>
      if (entries.size == 1) {
        val (valueSet, _) = entries.head
        proposals(valueSet) = preferred
      } else {
        // Multiple value sets compete for the same `<Field>Enum`
        // preferred name → each gets its own LCS-based form. The
        // `fieldPascal` is the same across the group (it's how they
        // ended up with the same preferred name), so the differing
        // half is the parent-derived prefix.
        //
        // Two passes:
        //
        //   1. Compute an LCS-based candidate per value set.
        //   2. Within the group, find the subset of value sets that
        //      collide on the same candidate. Surgical fallback:
        //      only the colliding value sets revert to
        //      first-parent-scoped naming. Non-colliding ones keep
        //      their LCS-based name. This preserves the clean
        //      `ActionDataStatusEnum`-style results when most of
        //      the group has a meaningful LCS and only a couple of
        //      siblings genuinely share the LCS with another set.
        val groupCandidates = entries.map { case (valueSet, ctx) =>
          val parents = ctx.parents.toList.sorted
          val rawLcs = if (parents.size > 1) longestCommonSuffix(parents) else parents.headOption.getOrElse("")
          // Word-align the LCS to a PascalCase boundary so we don't
          // produce names like `esRequestDTOResourceObjectAttributes…`
          // (a raw suffix match when parents differ in a mid-name
          // word: `…Values…` vs `…Series…` share the trailing "es"
          // plus the rest, but the visible cut should start at the
          // next real word boundary).
          val alignedLcs = {
            val firstUpper = rawLcs.indexWhere(_.isUpper)
            if (firstUpper > 0) rawLcs.substring(firstUpper) else rawLcs
          }
          val prefix =
            if (alignedLcs.length >= 3 && parents.forall(_.length > alignedLcs.length)) alignedLcs
            else parents.headOption.getOrElse("")
          (valueSet, ctx, parents, s"$prefix${ctx.fieldPascal}Enum")
        }
        val byCandidate = groupCandidates.groupBy(_._4)
        groupCandidates.foreach { case (valueSet, ctx, parents, candidate) =>
          val colliders = byCandidate(candidate)
          val name =
            if (colliders.size == 1) candidate
            else {
              // Multiple value sets in this group propose the same
              // LCS-based name → only the colliding ones fall back
              // to first-parent. Pre-sorted parents make "first"
              // deterministic across runs.
              val firstParent = parents.headOption.getOrElse("")
              s"$firstParent${ctx.fieldPascal}Enum"
            }
          proposals(valueSet) = name
        }
      }
    }

    // Resolve collisions between proposed names. Two value sets
    // could propose the same LCS-derived name (e.g. parents in two
    // value sets share the same LCS); disambiguate with a numeric
    // suffix on the second-and-later contestants. Order is sorted
    // by value-set content for determinism across runs.
    val finalNames = scala.collection.mutable.Map.empty[List[String], String]
    val usedNames = scala.collection.mutable.Set.empty[String] ++ claimed
    proposals.toList.sortBy(_._1.mkString("|")).foreach { case (valueSet, candidate) =>
      var name = candidate
      var i = 2
      while (usedNames.contains(name) && !inlineEnumByValues.get(valueSet).contains(name)) {
        name = s"${candidate}_$i"
        i += 1
      }
      finalNames(valueSet) = name
      usedNames += name
    }

    // Build the old → new rename map, dropping no-ops.
    finalNames.iterator.flatMap { case (valueSet, newName) =>
      inlineEnumByValues.get(valueSet).filter(_ != newName).map(old => old -> newName)
    }.toMap
  }

  /** Bare type names referenced from a set of operations' parameter
    * types and success types. Ignores qualified Refs (those point at
    * `klaviyo4s-core` types which are never emitted from the codegen
    * and don't participate in the reachability walk).
    */
  private def collectReferencedTypes(ops: List[OperationPlan]): Set[String] = {
    val out = scala.collection.mutable.Set.empty[String]
    def collect(t: ScalaType): Unit = t match {
      case ScalaType.Ref(qn) if !qn.contains('.') => out += qn
      case ScalaType.App(_, args) => args.foreach(collect)
      case _ => ()
    }
    ops.foreach { op =>
      op.parameters.foreach(p => collect(p.scalaType))
      collect(op.successType)
    }
    out.toSet
  }

  /** Expand a seed set of bare type names by walking the structural
    * references each type makes to other types. Continues until a
    * fixed point is reached. Qualified refs (`com.alexdupre.…`) are
    * out of scope and are skipped.
    */
  private def expandReachable(seed: Set[String], allTypes: List[TypeDef]): Set[String] = {
    val byName = allTypes.iterator.map(t => t.name -> t).toMap
    val visited = scala.collection.mutable.Set.empty[String] ++ seed
    var frontier: Set[String] = seed
    while (frontier.nonEmpty) {
      val next = frontier.flatMap(name => byName.get(name).fold(Set.empty[String])(refsOf)).filterNot(visited.contains)
      visited ++= next
      frontier = next
    }
    visited.toSet
  }

  /** All bare type names that `td` references through its own structure
    * (record field types, union variant types, primitive-union value
    * types). Qualified refs are filtered out — see [[expandReachable]].
    */
  private def refsOf(td: TypeDef): Set[String] = {
    val out = scala.collection.mutable.Set.empty[String]
    def collect(t: ScalaType): Unit = t match {
      case ScalaType.Ref(qn) if !qn.contains('.') => out += qn
      case ScalaType.App(_, args) => args.foreach(collect)
      case _ => ()
    }
    td match {
      case r: TypeDef.Record => r.fields.foreach(f => collect(f.scalaType))
      case _: TypeDef.StringEnum => ()
      case u: TypeDef.PrimitiveUnion => u.variants.foreach(v => collect(v.scalaType))
      case u: TypeDef.ResourceUnion => u.variants.foreach(v => collect(v.scalaType))
    }
    out.toSet
  }

  /** Map a Klaviyo tag (`"Custom Objects"`, `"Webhooks"`, ...) to the
    * generated package name. Lowercases and strips spaces, underscores
    * and hyphens — same rule we apply to the legacy per-category file
    * stems for consistency.
    */
  private[model] def tagToPackageName(tag: String): String =
    tag.toLowerCase.replaceAll("[\\s_\\-]+", "")

  /** Map a Klaviyo tag to the PascalCased base name of the generated
    * Api class. Emitter appends `Api` (so `"Custom Objects"` becomes
    * `CustomObjectsApi`).
    */
  private[model] def tagToClassName(tag: String): String =
    tag.split("[\\s_\\-]+").filter(_.nonEmpty).map(capitalize).mkString

  /** Decide whether a top-level schema is a string enum or a record,
    * and recursively walk it.
    */
  private def toTypeDef(
    name: String,
    rawSchema: RawSchema,
    parent: Option[String] = None,
    shortName: Option[String] = None
  ): TypeDef = {
    // Flatten any `allOf` composition before inspecting `properties` /
    // `required` — Klaviyo uses `allOf` as inheritance for JSON:API
    // response data shapes and we want to see the merged set of
    // fields, not just the locally-declared ones.
    val schema = flattenAllOf(rawSchema, visited = Set.empty)
    // Treat only homogeneous *string*-valued enums as scala enums.
    // Boolean / numeric singletons are JSON-Schema's way of pinning a
    // literal discriminator; we emit them as plain typed fields (the
    // codec still round-trips correctly, just without the enum
    // constraint at the Scala level). Phase 5 may revisit.
    if (schema.enumValues.kind == EnumValues.Kind.Strings && schema.`type`.contains("string")) {
      TypeDef.StringEnum(name, schema.enumValues.asStrings, schema.description)
    } else {
      // Treat anything with `properties` (or declared as `object`) as
      // a record. Fallback to an empty record for unsupported shapes;
      // those cases will surface in the codegen tests and we add
      // handling at that point.
      // Two output buckets: the user-visible fields on the case
      // class constructor, and a side-channel of "hidden
      // discriminators" — required+non-nullable single-value-enum
      // fields the emitter handles via a Wire-shim codec instead of
      // exposing on the public type. The Wire shim writes the
      // constant on encode and validates it on decode, removing the
      // `type = AccountEnum.Account`-style boilerplate from every
      // call site.
      val visibleFields = scala.collection.mutable.ListBuffer.empty[FieldPlan]
      val hiddenDiscrims = scala.collection.mutable.ListBuffer.empty[HiddenDiscriminator]
      schema.properties.foreach { case (jsonName, fieldSchema) =>
        // `readOnly: true` (server-set fields like `id`, `created_at`,
        // `updated_at`) flips `required` off regardless of what the
        // spec's own `required` array says. The field stays in the
        // type and decodes normally on response shapes; users get to
        // omit it on request shapes. `nullable` is preserved as-is
        // — a readOnly + nullable field maps to `Tristate.Maybe[T]`,
        // a readOnly + non-nullable to `Tristate.Optional[T]`.
        val readOnly = fieldSchema.readOnly.contains(true)
        val isRequired = !readOnly && schema.required.contains(jsonName)
        val isNullable = fieldSchema.nullable.contains(true)

        // Detect a JSON:API-style required + non-nullable
        // single-value enum field. When found, hide it: the emitter
        // generates a Wire-shim codec carrying the constant. Otherwise
        // the field flows through to the user-facing case class.
        val asHiddenConstant: Option[String] =
          if (isRequired && !isNullable) singleStringEnumOf(fieldSchema) else None

        asHiddenConstant match {
          case Some(constant) =>
            hiddenDiscrims += HiddenDiscriminator(jsonName, constant)
          case None =>
            val scalaType = toScalaType(fieldSchema, parentName = name, fieldName = jsonName)
            val default = fieldSchema.default.flatMap(renderDefault(_, scalaType, isRequired, isNullable))
            visibleFields += FieldPlan(
              scalaName = toScalaIdentifier(jsonName),
              jsonName = jsonName,
              scalaType = scalaType,
              required = isRequired,
              nullable = isNullable,
              doc = fieldSchema.description,
              default = default
            )
        }
      }
      TypeDef.Record(
        name = name,
        fields = visibleFields.toList,
        doc = schema.description,
        hiddenDiscriminators = hiddenDiscrims.toList,
        parent = parent,
        shortName = shortName
      )
    }
  }

  /** If `schema` resolves (via `$ref` or inline) to a string enum
    * with exactly one value, return that value; otherwise `None`.
    * Used to detect JSON:API-style discriminator fields that the
    * emitter handles via a Wire-shim codec.
    */
  private def singleStringEnumOf(schema: RawSchema): Option[String] = {
    schema.ref match {
      case Some(ref) =>
        val target = ref.stripPrefix("#/components/schemas/")
        if (target.contains('/')) None
        else resolveRef(ref).flatMap(singleStringEnumValue)
      case None =>
        if (schema.`type`.contains("string")) singleStringEnumValue(schema) else None
    }
  }

  /** Walk a schema to a Scala type expression. Anonymous inline object
    * schemas are extracted into named records via [[synthesize]] and
    * replaced with a reference to the new name.
    *
    * Flattens any field-level `allOf` composition first, so a field
    * declared as `allOf: [{$ref:Foo}, {properties:{...extra...}}]`
    * becomes a synthetic record with the merged property set.
    */
  private def toScalaType(rawField: RawSchema, parentName: String, fieldName: String): ScalaType = {
    // GUARD: anyOf. We don't generate sealed unions for `anyOf`
    // shapes today (no Klaviyo category uses them as of the last
    // refresh). Fail loudly if a refresh introduces them so we make
    // a deliberate decision rather than emitting `String` by accident.
    if (rawField.anyOf.nonEmpty) {
      sys.error(
        s"Unsupported spec shape: '$parentName.$fieldName' uses `anyOf` (${rawField.anyOf.size} items). " +
          s"`anyOf` semantics need explicit handling — extend Planner.toScalaType."
      )
    }
    // GUARD: deep $ref. We only resolve top-level component refs;
    // anything with extra path segments (`#/components/schemas/A/properties/B`)
    // would silently produce a `Ref(B)` to a type that doesn't exist
    // at the top level. Fail explicitly so the case gets a real fix.
    rawField.ref.foreach { ref =>
      val stripped = ref.stripPrefix("#/components/schemas/")
      if (stripped.contains('/')) {
        sys.error(
          s"Unsupported spec shape: '$parentName.$fieldName' references a deep JSON pointer '$ref'. " +
            s"The planner only resolves top-level component schemas — extend resolveRef to support deeper paths."
        )
      }
    }
    // Special-case the very common shape `allOf:[{$ref:Foo}]` (a
    // single-ref composition, often paired with `nullable:true`) —
    // emit a plain ref to Foo without synthesizing a new record.
    if (rawField.allOf.lengthCompare(1) == 0 && rawField.properties.isEmpty && rawField.ref.isEmpty) {
      val solo = rawField.allOf.head
      if (solo.ref.isDefined) return toScalaType(solo.copy(nullable = rawField.nullable), parentName, fieldName)
    }
    // `oneOf` of primitives → synthesise a sealed union (gap #3).
    // We deliberately keep the heuristic narrow: every item must be a
    // raw primitive, no `$ref`s, no object-typed items. Anything more
    // exotic (object oneOf, mixed $ref/inline) falls through to the
    // generic handling below and may end up as a String fallback.
    if (rawField.oneOf.nonEmpty && rawField.ref.isEmpty && rawField.properties.isEmpty) {
      val fieldPascal = pascalCase(fieldName)
      val requested = s"$parentName$fieldPascal"
      asPrimitiveUnion(rawField.oneOf) match {
        case Some(variants) =>
          val finalName = synthesizePrimitiveUnion(
            requestedName = requested,
            variants = variants,
            doc = rawField.description,
            parent = Some(parentName),
            shortName = Some(fieldPascal)
          )
          return ScalaType.Ref(finalName)
        case None => () // fall through
      }
      // `oneOf` of `$ref`s to JSON:API resources → resource union
      // discriminated by the wire `type` field (gap #4).
      //
      // Even when some variants share the same discriminator value
      // (e.g. Klaviyo's segment filters with multiple shapes carrying
      // `type: "date"`), the hybrid path emits a proper sealed-trait
      // union: variants with unique discriminators stay typed, while
      // the conflicting subset collapses into a single merged
      // "<Disc>Variant" arm. The all-collapse fallback below only
      // fires when no variant exposes a discriminator at all.
      asResourceUnion(rawField.oneOf, parentName, fieldPascal) match {
        case Some(variants) =>
          val finalName = synthesizeResourceUnion(
            requestedName = requested,
            variants = variants,
            doc = rawField.description,
            parent = Some(parentName),
            shortName = Some(fieldPascal)
          )
          return ScalaType.Ref(finalName)
        case None => ()
      }
      // Last-resort handling for `oneOf` of `$ref`s that share no
      // JSON:API `type` discriminator at all (e.g. Klaviyo's
      // send-options, send-strategy, tracking-options unions). Merge
      // every variant's properties into a single synthetic record,
      // treating any field that doesn't appear in EVERY variant as
      // optional. Decoding then succeeds for any variant; callers
      // can dispatch on whatever discriminator field the merged
      // record exposes (e.g. `method` on send strategies).
      val refTargets = rawField.oneOf.flatMap(_.ref.map(_.stripPrefix("#/components/schemas/")))
      if (refTargets.size == rawField.oneOf.size) {
        return ScalaType.Ref(synthesizeMergedOneOfRecord(
          refTargets = refTargets,
          items = rawField.oneOf,
          description = rawField.description,
          parentName = parentName,
          // All-collapse: the merged record is the only thing produced
          // for this field, so it naturally claims the field's name
          // slot in the parent companion.
          effectiveShortName = fieldPascal
        ))
      }
    }
    val schema = flattenAllOf(rawField, visited = Set.empty)
    // 1. $ref takes precedence over everything else.
    schema.ref match {
      case Some(refStr) =>
        val target = refStr.split('/').last
        sharedSchemaRefs.get(target) match {
          case Some(qualified) => ScalaType.Ref(qualified)
          case None => ScalaType.Ref(target)
        }
      case None =>
        // After flattening, a schema may carry only `properties`
        // without an explicit `type: "object"` (Klaviyo sometimes
        // declares the type at the parent level and omits it on the
        // allOf items). Treat that as an object so we synthesize a
        // record rather than falling through to the String fallback.
        val effectiveType = schema.`type`.orElse(if (schema.properties.nonEmpty) Some("object") else None)
        effectiveType match {
          case Some("string") =>
            // String schemas carrying an inline string-enum get
            // promoted to a synthesised named enum. The synthesiser
            // dedupes on value set, so the preferred name is the
            // short `<FieldName>Enum` form — identical value sets
            // across the spec collapse to one shared type. When two
            // different value sets request the SAME preferred name
            // (e.g. distinct `sort` enums per endpoint), the
            // disambiguation falls back to `<ParentType><FieldName>Enum`
            // (which encodes the calling context) rather than an
            // opaque numeric suffix.
            if (schema.enumValues.kind == EnumValues.Kind.Strings && schema.enumValues.nonEmpty) {
              val fieldPascal = pascalCase(fieldName)
              val finalName = synthesizeStringEnum(
                preferred = s"${fieldPascal}Enum",
                parentScoped = s"$parentName${fieldPascal}Enum",
                parentName = parentName,
                fieldPascal = fieldPascal,
                values = schema.enumValues.asStrings,
                doc = schema.description
              )
              ScalaType.Ref(finalName)
            } else {
              // Honour OpenAPI's `format` hint for `type: string`.
              // Klaviyo's spec uses four format values today:
              //   - `date`      → `java.time.LocalDate`
              //   - `date-time` → `java.time.OffsetDateTime` (ISO-8601
              //     with a zone offset; Klaviyo's wire shape always
              //     includes one)
              //   - `uri`       → `java.net.URI`
              //   - `binary`    → `java.nio.file.Path` (multipart
              //     uploads only; the emitter's multipart code path
              //     reads bytes from the path)
              // Other formats (`time`, ...) fall through to `String`.
              // The corresponding codecs live in
              // `com.alexdupre.klaviyo.core.Codecs` and are imported
              // into every generated DTO file.
              schema.format match {
                case Some("date") => ScalaType.Ref("java.time.LocalDate")
                case Some("date-time") => ScalaType.Ref("java.time.OffsetDateTime")
                case Some("uri") => ScalaType.Ref("java.net.URI")
                case Some("binary") => ScalaType.Ref("java.nio.file.Path")
                case _ => ScalaType.Str
              }
            }
          case Some("integer") =>
            // OpenAPI 3.0: `integer` defaults to 32-bit unless `format: int64`
            // says otherwise. Defaulting to `Long` (the previous behaviour)
            // forced callers to write `pageSize = 100L` for fields that
            // Klaviyo declares as `type: integer` with no format.
            schema.format match {
              case Some("int64") => ScalaType.I64
              case _ => ScalaType.I32
            }
          case Some("number") =>
            // OpenAPI 3.0: `number` is a floating-point value; `format`
            // distinguishes 32-bit (`float`) from 64-bit (`double`).
            // Default to `Double` when unspecified — Klaviyo's money /
            // ratio fields fit comfortably there.
            schema.format match {
              case Some("float") => ScalaType.Dbl // F32 isn't worth the precision risk; keep Double.
              case _ => ScalaType.Dbl
            }
          case Some("boolean") => ScalaType.Bool
          case Some("array") =>
            val inner = schema.items
              .map(toScalaType(_, parentName, fieldName))
              .getOrElse(ScalaType.Str)
            ScalaType.App("Vector", List(inner))
          case Some("object") =>
            // An open `type: object` schema — no `properties`, no
            // composition, no `$ref` — is OpenAPI for "any JSON
            // object the user chooses". Klaviyo uses this for
            // template-render context bags, custom profile
            // properties, event-payload blobs, webhook headers,
            // etc. Generating an empty case class for these would
            // be a footgun (it always serialises to `{}` and drops
            // anything decoded), so we render them as `RawJson`
            // instead — a passthrough that lets users supply any
            // JSON text and round-trips wire bytes verbatim.
            if (schema.properties.isEmpty && schema.allOf.isEmpty && schema.oneOf.isEmpty) {
              ScalaType.Ref("com.alexdupre.klaviyo.core.RawJson")
            } else {
              // Inline anonymous object — synthesize a named record
              // and refer to it. `pascalCase` (not `capitalize`) so
              // a parent field named `webhook-topics` produces
              // `WebhookTopics` and never lands a hyphen inside a
              // Scala identifier.
              //
              // Tag the synthesised record with `parent = parentName`
              // and a short suffix so the emitter can nest it inside
              // the parent's companion object. The full plan-level
              // name stays parent-prefixed for reachability/cache
              // purposes; only the rendered form changes.
              val shortNm = pascalCase(fieldName)
              val finalName =
                synthesize(s"$parentName$shortNm", schema, parent = Some(parentName), shortName = Some(shortNm))
              ScalaType.Ref(finalName)
            }
          case _ =>
            // Unknown / unsupported shape. Default to String so the
            // codegen continues to make progress; we'll see this in
            // tests if it ever happens in a real spec.
            ScalaType.Str
        }
    }
  }

  /** Convert a raw operation into an [[OperationPlan]]. */
  private def toOperation(method: String, pathTemplate: String, op: RawOperation): OperationPlan = {
    val scalaName = toCamelCase(op.operationId)
    val regularPs = op.parameters.map(toParam(_, scalaName))
    val (bodyParam, bodyCt) = toBodyParam(op, scalaName) match {
      case Some((p, ct)) => (Some(p), Some(ct))
      case None => (None, None)
    }
    val filterPlan = toFilterPlan(op, scalaName)

    // When the op has a typed filter, retype its `filter` query
    // parameter from the spec's plain `string` to the runtime
    // [[com.alexdupre.klaviyo.core.Filter]] opaque alias. The
    // emitter then renders `.render` instead of `.toString` and
    // serialises the wire-format value.
    val withFilterTyped =
      if (filterPlan.isEmpty) regularPs
      else regularPs.map { p =>
        if (p.jsonName == "filter")
          p.copy(scalaType = ScalaType.Ref("com.alexdupre.klaviyo.core.Filter"))
        else p
      }
    val params = withFilterTyped ++ bodyParam.toList

    // Pick the 2xx response's JSON:API content schema as the success
    // type. We prefer `application/vnd.api+json` (Klaviyo's standard
    // content type); fall back to `application/json` if absent.
    //
    // Endpoints with only a 204 No Content response carry no body;
    // we model them as `Unit` so the emitter generates a method that
    // returns `F[Unit]` without trying to decode the empty body.
    val successType =
      op.responses
        .get("200")
        .orElse(op.responses.get("201"))
        .orElse(op.responses.get("202"))
        .flatMap { r =>
          r.content.get("application/vnd.api+json").orElse(r.content.get("application/json"))
        }
        .flatMap(_.schema)
        .map(toScalaType(_, parentName = capitalize(scalaName), fieldName = "Response"))
        .getOrElse(ScalaType.Unit)

    OperationPlan(
      scalaName = scalaName,
      httpMethod = method,
      pathTemplate = pathTemplate,
      parameters = params,
      successType = successType,
      summary = op.summary,
      description = op.description,
      filter = filterPlan,
      bodyContentType = bodyCt
    )
  }

  /** Lift `x-klaviyo-filters` (if present) into a typed plan that the
    * emitter will turn into a `<OpPascal>Filter` Scala object beside
    * the API class.
    *
    * Returns `None` if the operation does not declare any filters,
    * so the emitter knows to fall back to the plain-string `filter`
    * query parameter shape.
    */
  private def toFilterPlan(op: RawOperation, scalaOpName: String): Option[FilterPlan] = {
    if (op.xKlaviyoFilters.isEmpty) None
    else {
      val fields = op.xKlaviyoFilters.toList.map { case (wireName, raw) =>
        val (valueKind, enumValues) = filterValueKindOf(raw.value)
        FilterFieldPlan(
          jsonName = wireName,
          scalaName = filterFieldNameToScala(wireName),
          valueKind = valueKind,
          singleOps = raw.operators.single,
          listOps = raw.operators.list,
          noneOps = raw.operators.none,
          enumValues = enumValues
        )
      }
      Some(FilterPlan(objectName = s"${capitalize(scalaOpName)}Filter", fields = fields))
    }
  }

  /** Convert a `x-klaviyo-filters` field name (which may contain
    * dots for nested-path filtering, e.g. `messages.channel`) to a
    * Scala identifier (`messagesChannel`).
    */
  private[model] def filterFieldNameToScala(wireName: String): String = {
    val parts = wireName.split("[._\\-]").filter(_.nonEmpty)
    val camel =
      if (parts.isEmpty) wireName
      else parts.head + parts.tail.map(capitalize).mkString
    if (reservedWords.contains(camel)) s"`$camel`" else camel
  }

  /** Decide which [[FilterValueKind]] applies to an inline filter
    * value schema. Also extracts any `enum` constraint for scaladoc
    * inclusion. Defaults to `Str` for unrecognised shapes — the most
    * permissive choice in Klaviyo's filter grammar.
    */
  private def filterValueKindOf(schemaOpt: Option[RawSchema]): (FilterValueKind, List[String]) = {
    val schema = schemaOpt.getOrElse(RawSchema())
    val enums = schema.enumValues.asStrings
    val kind = schema.`type` match {
      case Some("boolean") => FilterValueKind.Bool
      case Some("integer") =>
        if (schema.format.contains("int64")) FilterValueKind.Int_
        else FilterValueKind.Int_
      case Some("number") => FilterValueKind.Dbl
      case Some("string") =>
        if (schema.format.contains("date-time")) FilterValueKind.DateTime
        else FilterValueKind.Str
      case _ => FilterValueKind.Str
    }
    (kind, enums)
  }

  /** Lift `requestBody.content` into a synthetic `body` parameter and
    * report which content type the spec declared for it. Returns
    * `None` if the operation has no request body.
    *
    * Three content types are recognised, in priority order:
    *   - `application/vnd.api+json` (JSON:API default; the emitter
    *     writes a single JSON document).
    *   - `application/json` (plain JSON; emitter handles identically).
    *   - `multipart/form-data` (file-upload endpoints like
    *     `upload_image_from_file`; the emitter renders multipart parts
    *     instead).
    *
    * The parameter is named `body` to keep call sites short. It is
    * `required = true` unless the spec says otherwise; the emitter
    * wraps non-required bodies in `Tristate` like any other field.
    *
    * The `parentName` for the synthetic schema name uses the
    * operation's PascalCased name so any inline body shape gets a
    * sensible name (`CreateEventBody` rather than a collision-prone
    * generic).
    */
  private def toBodyParam(op: RawOperation, scalaOpName: String): Option[(ParamPlan, String)] = {
    op.requestBody.flatMap { rb =>
      val mediaPriority = List("application/vnd.api+json", "application/json", "multipart/form-data")
      val matched = mediaPriority.iterator.flatMap(ct => rb.content.get(ct).map(ct -> _)).take(1).toList.headOption
      matched.flatMap { case (contentType, media) =>
        media.schema.map { schema =>
          val tpe = toScalaType(schema, parentName = s"${capitalize(scalaOpName)}Body", fieldName = "Payload")
          val p = ParamPlan(
            scalaName = "body",
            jsonName = "body",
            location = ParamLocation.Body,
            scalaType = tpe,
            required = rb.required.getOrElse(true),
            defaultValue = None,
            description = rb.description
          )
          (p, contentType)
        }
      }
    }
  }

  private def toParam(p: RawParameter, scalaOpName: String): ParamPlan = {
    val jsonName = p.name.getOrElse("")
    val location = p.in match {
      case Some("path") => ParamLocation.Path
      case Some("header") => ParamLocation.Header
      case _ => ParamLocation.Query
    }
    // Parent prefix includes the operation name so per-endpoint inline
    // enums (e.g. the `sort` query param, whose accepted values differ
    // across categories) get distinct synthesised type names rather
    // than all colliding on `ParamSort`.
    val parent = s"${capitalize(scalaOpName)}Param"
    val scalaType = p.schema
      .map(toScalaType(_, parentName = parent, fieldName = jsonName))
      .getOrElse(ScalaType.Str)
    ParamPlan(
      scalaName = toScalaIdentifier(jsonName),
      jsonName = jsonName,
      location = location,
      scalaType = scalaType,
      required = p.required.getOrElse(false),
      defaultValue = None,
      description = p.description
    )
  }

  /** Resolve a `#/components/schemas/Foo` style reference to its
    * target schema. Returns `None` for unknown refs and for nested
    * paths beyond top-level (Klaviyo doesn't use those today).
    */
  private def resolveRef(ref: String): Option[RawSchema] = {
    // We only handle the canonical top-level form; anything else
    // (deep JSON pointers, external refs) is silently treated as
    // "unknown" and the planner falls through to the raw schema.
    val name = ref.stripPrefix("#/components/schemas/")
    if (name.contains('/')) None else raw.components.schemas.get(name)
  }

  /** Resolve `allOf` composition by recursively flattening each item
    * (`$ref` resolved against the component map, inline items walked
    * for their own `allOf`) and merging the union of `properties`,
    * `required` and `type` into a single normalised schema.
    *
    * Merge rules:
    *   - properties are unioned, preserving first-seen insertion
    *     order; later items / the parent schema override on name
    *     collision (rare in Klaviyo — names are unique)
    *   - `required` is the union deduplicated
    *   - the parent's own `description` wins over inherited
    *
    * Cycles are broken by the `visited` set — a cyclic $ref resolves
    * to an empty schema rather than spinning forever. Klaviyo does
    * not have cycles today but the guard costs nothing.
    *
    * Other composition keywords (`oneOf`, `anyOf`) are NOT flattened
    * here; they need different machinery (sealed unions) handled
    * separately in runtime gap #3.
    */
  private def flattenAllOf(schema: RawSchema, visited: Set[String]): RawSchema = {
    if (schema.allOf.isEmpty) schema
    else {
      val items = schema.allOf.map { item =>
        item.ref match {
          case Some(ref) =>
            val name = ref.stripPrefix("#/components/schemas/")
            if (visited.contains(name)) RawSchema()
            else resolveRef(ref) match {
              case Some(target) => flattenAllOf(target, visited + name)
              case None => item // unknown ref — keep, planner will emit a Ref
            }
          case None =>
            flattenAllOf(item, visited)
        }
      }
      // Property merge is *deep*: when two items declare the same
      // property name and both values are object-like (have their own
      // `properties`), we merge their nested property maps too. This
      // is the only way Klaviyo's pattern of split-attribute
      // declarations across multiple `allOf` siblings round-trips
      // correctly — e.g. one item declares `attributes.email` and a
      // sibling declares `attributes.subscriptions`, and both must
      // appear on the final record.
      val mergedProps = (items.iterator ++ Iterator.single(schema)).foldLeft(ListMap.empty[String, RawSchema]) {
        (acc, item) =>
          item.properties.foldLeft(acc) { case (a, (k, v)) =>
            a.get(k) match {
              case Some(existing) => a + (k -> mergeSchemas(existing, v))
              case None => a + (k -> v)
            }
          }
      }
      val mergedRequired = (items.flatMap(_.required) ++ schema.required).distinct
      schema.copy(
        allOf = Nil,
        properties = mergedProps,
        required = mergedRequired,
        `type` = schema.`type`.orElse(items.iterator.flatMap(_.`type`).find(_ => true)),
        description = schema.description.orElse(items.iterator.flatMap(_.description).find(_ => true))
      )
    }
  }

  /** Interpret a `oneOf` array as a primitive union, if every item
    * is a simple primitive type. Returns `None` (no synthesised
    * union) for any other shape — the caller falls back to its
    * generic handling.
    */
  private def asPrimitiveUnion(items: List[RawSchema]): Option[List[PrimitiveVariant]] = {
    val seen = scala.collection.mutable.LinkedHashMap.empty[PrimitiveKind, PrimitiveVariant]
    val ok = items.forall { item =>
      if (item.ref.isDefined || item.allOf.nonEmpty || item.oneOf.nonEmpty || item.properties.nonEmpty)
        false
      else
        item.`type` match {
          case Some("string") =>
            seen.getOrElseUpdate(PrimitiveKind.Str, PrimitiveVariant("StringValue", PrimitiveKind.Str, ScalaType.Str))
            true
          case Some("integer") =>
            seen.getOrElseUpdate(PrimitiveKind.Num, PrimitiveVariant("NumberValue", PrimitiveKind.Num, ScalaType.I64))
            true
          case Some("number") =>
            seen.getOrElseUpdate(PrimitiveKind.Num, PrimitiveVariant("NumberValue", PrimitiveKind.Num, ScalaType.Dbl))
            true
          case Some("boolean") =>
            seen.getOrElseUpdate(
              PrimitiveKind.Bool,
              PrimitiveVariant("BooleanValue", PrimitiveKind.Bool, ScalaType.Bool)
            )
            true
          case Some("null") =>
            seen.getOrElseUpdate(PrimitiveKind.Null, PrimitiveVariant("NullValue", PrimitiveKind.Null, ScalaType.Unit))
            true
          case _ => false
        }
    }
    if (ok && seen.size >= 2) Some(seen.values.toList) else None
  }

  /** Register a synthesised primitive union in the type buffer. Like
    * [[synthesize]], but produces a [[TypeDef.PrimitiveUnion]].
    *
    * @return the final (possibly suffixed) name actually registered
    */
  private def synthesizePrimitiveUnion(
    requestedName: String,
    variants: List[PrimitiveVariant],
    doc: Option[String],
    parent: Option[String],
    shortName: Option[String]
  ): String = {
    val name = uniqueSyntheticName(requestedName)
    // If the requested name had to be suffixed for uniqueness, drop
    // the nesting metadata — the resulting `name` no longer aligns
    // with `parent + shortName`, and trying to nest under a longer
    // name would be misleading.
    val (effParent, effShort) =
      if (name == requestedName) (parent, shortName) else (None, None)
    if (!synthesized.exists(_.name == name))
      synthesized += TypeDef.PrimitiveUnion(name, variants, doc, effParent, effShort)
    name
  }

  /** Interpret a `oneOf` array as a resource union: each item must
    * be a `$ref` to a top-level component schema that itself looks
    * like a JSON:API resource (has a `type` property whose schema is
    * a string enum naming the discriminator value).
    *
    * Returns `None` if any item is not a clean ref or if any
    * referenced schema doesn't expose a recognisable `type`
    * discriminator — the caller falls back to the merged-record
    * path. When every variant contributes a discriminator, the
    * result is a list of [[ResourceVariant]]s **grouped by
    * discriminator**:
    *
    *   - Variants with a unique discriminator stay 1:1 with the
    *     original `$ref`.
    *   - Variants sharing a discriminator value (e.g. two flow
    *     triggers both carrying `type: "date"`) are merged into a
    *     single synthetic record via [[synthesizeMergedOneOfRecord]],
    *     and represented by one variant pointing at the merged
    *     record. The outer dispatch on `type` still picks this arm
    *     unambiguously; users internally see a case class with the
    *     union of all conflicting variants' fields.
    *
    * Has side effects: synthesising a merged record for any
    * conflicting group registers it on [[synthesized]] / [[mergedOneOfCache]] /
    * [[mergedOneOfParents]] just like the all-collapse path.
    */
  private def asResourceUnion(
    items: List[RawSchema],
    parentName: String,
    fieldPascal: String
  ): Option[List[ResourceVariant]] = {
    // Resolve each item to (primary disc, target ref, original item,
    // resolved + flattened schema). The resolved/flat schema is kept
    // around so [[tryMultiFieldDiscriminator]] can inspect properties
    // for a secondary tie-breaker without re-running resolveRef.
    val resolveds: List[Option[(String, String, RawSchema, RawSchema)]] = items.map { item =>
      item.ref.flatMap { ref =>
        val target = ref.stripPrefix("#/components/schemas/")
        if (target.contains('/')) None
        else resolveRef(ref).flatMap { resolved =>
          val flat = flattenAllOf(resolved, visited = Set.empty)
          discriminatorOf(resolved).map(d => (d, target, item, flat))
        }
      }
    }
    if (resolveds.exists(_.isEmpty)) None
    else {
      // Group by primary discriminator, preserving the first-appearance
      // order of each distinct discriminator so the emitted sealed-trait's
      // case order is stable and tracks the spec.
      val resolvedList = resolveds.flatten
      val grouped: List[(String, List[(String, RawSchema, RawSchema)])] = {
        val byDisc = resolvedList.groupBy(_._1).map { case (k, vs) =>
          k -> vs.map { case (_, target, item, flat) => (target, item, flat) }
        }
        resolvedList.map(_._1).distinct.map(k => k -> byDisc(k))
      }
      val finalVariants: List[ResourceVariant] = grouped.iterator.flatMap { case (disc, members) =>
        members match {
          case (target, _, _) :: Nil =>
            List(ResourceVariant(
              caseName = s"${pascalCase(disc)}Variant",
              discriminator = List(PrimaryDiscriminatorField -> disc),
              scalaType = ScalaType.Ref(target)
            ))
          case multiple =>
            // Conflict on the primary discriminator. First try to
            // tie-break by a SECONDARY single-value-enum field that is
            // distinct across the group. If that succeeds we keep
            // proper per-variant typing. Otherwise fall back to
            // merging — the merged record collapses internal
            // type-safety for the group but the outer dispatch still
            // works.
            tryMultiFieldDiscriminator(disc, multiple) match {
              case Some(splitVariants) => splitVariants
              case None =>
                List(ResourceVariant(
                  caseName = s"${pascalCase(disc)}Variant",
                  discriminator = List(PrimaryDiscriminatorField -> disc),
                  scalaType = ScalaType.Ref(synthesizeMergedOneOfRecord(
                    refTargets = multiple.map(_._1),
                    items = multiple.map(_._2),
                    description = None,
                    parentName = parentName,
                    effectiveShortName = s"${pascalCase(disc)}$fieldPascal"
                  ))
                ))
            }
        }
      }.toList
      // Same fall-through rule as before: if the final variant list
      // collapses to a single arm (whole oneOf shares a discriminator
      // AND no tie-breaker was found, or the oneOf had only one item),
      // a sealed-trait wrapper adds no value. Let the caller's
      // all-collapse path emit a plain merged record instead.
      if (finalVariants.size <= 1) None else Some(finalVariants)
    }
  }

  /** JSON:API discriminator field name. Klaviyo's schemas consistently
    * use `type` as the primary discriminator across every union, so
    * the planner hard-codes that name here. If a future spec ever uses
    * a different field, this is the single point of change.
    */
  private val PrimaryDiscriminatorField: String = "type"

  /** Attempt to disambiguate a group of resource-union variants that
    * share the same primary discriminator value. Looks for a single
    * SECONDARY field that is:
    *
    *   - present on every member;
    *   - declared as a single-value string enum on every member;
    *   - assigned a DISTINCT value across all members.
    *
    * The first qualifying candidate (in property declaration order)
    * wins — fields are iterated as they appear on the first member,
    * which matches the spec's authoring order and keeps emitted code
    * stable across regenerations.
    *
    * Returns `Some(List[ResourceVariant])` with one variant per
    * member when a tie-breaker is found. Each variant carries a
    * composite discriminator (`[("type", primary), (fieldName,
    * secondaryValue)]`) and points directly at the member's
    * resource schema. Returns `None` otherwise, signalling the
    * caller to fall back to merging.
    */
  private def tryMultiFieldDiscriminator(
    primaryDisc: String,
    members: List[(String, RawSchema, RawSchema)]
  ): Option[List[ResourceVariant]] = {
    // For each member, build a map: candidate-field-name → its
    // single-value-enum string value. Skip the primary discriminator
    // field; everything else is a candidate.
    val candidateMaps: List[Map[String, String]] = members.map { case (_, _, flat) =>
      flat.properties.iterator.flatMap {
        case (name, prop) if name != PrimaryDiscriminatorField =>
          singleStringEnumValue(prop).map(v => name -> v)
        case _ => None
      }.toMap
    }
    // Intersect candidate field names so we only consider fields
    // present on every member. Preserve the first member's
    // declaration order for deterministic emission.
    val firstMemberOrder: List[String] = members.head._3.properties.keys
      .filter(k => k != PrimaryDiscriminatorField && candidateMaps.forall(_.contains(k)))
      .toList
    // First field whose values are pairwise distinct wins.
    firstMemberOrder.iterator.flatMap { field =>
      val values = candidateMaps.map(_(field))
      if (values.distinct.size == values.size) Some(field -> values) else None
    }.collectFirst { case x => x }.map { case (tieBreakerField, values) =>
      members.zip(values).map { case ((target, _, _), v) =>
        ResourceVariant(
          caseName = s"${pascalCase(primaryDisc)}${pascalCase(v)}Variant",
          discriminator = List(
            PrimaryDiscriminatorField -> primaryDisc,
            tieBreakerField -> v
          ),
          scalaType = ScalaType.Ref(target)
        )
      }
    }
  }

  /** Merge a list of `$ref`-carrying schemas into one synthetic
    * record. Shared by the all-collapse fallback (when no variant
    * exposes a discriminator) and by [[asResourceUnion]] (when a
    * subset of variants share a discriminator value).
    *
    * Registers the result in [[mergedOneOfCache]] / [[mergedOneOfParents]]
    * so structurally identical groups across multiple parents collapse
    * to one type. Returns the final (collision-disambiguated) Scala
    * type name of the synthesised record.
    *
    * Naming follows the existing two-stage heuristic
    * ([[mergedOneOfName]] + parent-scoped fallback): prefer an
    * LCS-derived `AnyX` form, fall back to `<Parent><effectiveShortName>`
    * when the preferred form is too long or already claimed. The
    * post-plan single-parent nesting pass uses `effectiveShortName`
    * as the record's `shortName` when nesting it under its parent.
    *
    * @param effectiveShortName the name slot this record occupies
    *                           inside its parent's companion when
    *                           nested. The all-collapse path passes
    *                           the field's `fieldPascal` directly
    *                           (e.g. `Filter`, `Triggers`). The
    *                           hybrid resource-union path passes
    *                           `<PascalDisc><FieldPascal>` (e.g.
    *                           `DateTriggers`) so the merged record
    *                           doesn't compete with the sealed-trait
    *                           wrapper that already claims
    *                           `fieldPascal` as its own shortName.
    */
  private def synthesizeMergedOneOfRecord(
    refTargets: List[String],
    items: List[RawSchema],
    description: Option[String],
    parentName: String,
    effectiveShortName: String
  ): String = {
    val cacheKey = refTargets.toSet
    mergedOneOfParents.get(cacheKey) match {
      case Some(ctx) => ctx.parents += parentName
      case None =>
        mergedOneOfParents(cacheKey) = Planner.MergedOneOfContext(
          fieldPascal = effectiveShortName,
          parents = scala.collection.mutable.Set(parentName)
        )
    }
    mergedOneOfCache.get(cacheKey) match {
      case Some(existing) => existing
      case None =>
        mergedOneOfSchema(items) match {
          case Some(merged) =>
            val derived = mergedOneOfName(refTargets)
            val parentScoped = s"$parentName$effectiveShortName"
            val preferred = if (derived.length > MaxMergedOneOfNameLength) parentScoped else derived
            val finalName = synthesize(
              requestedName = preferred,
              schema = merged.copy(description = description.orElse(merged.description)),
              fallbackName = Some(parentScoped)
            )
            mergedOneOfCache(cacheKey) = finalName
            finalName
          case None =>
            // Shouldn't normally happen — refTargets was derived from
            // resolved refs, so mergedOneOfSchema can't fail unless
            // the spec mutates between calls. Fall back to the first
            // contributor's name rather than crash.
            refTargets.head
        }
    }
  }

  /** Extract the `type` discriminator value from a resource schema.
    * Two shapes are recognised:
    *
    *  1. JSON:API style — `properties.type` is a `$ref` to a top-level
    *     single-value string enum component (`AccountEnum`, `ListEnum`,
    *     …). The classic discriminator for Klaviyo resources.
    *
    *  2. Inline style — `properties.type` is itself an inline
    *     `{ type: string, enum: [<one value>] }` schema. Used by
    *     Klaviyo's segment / filter / condition unions
    *     (`StringOperatorStringFilter.type: ["string"]`, etc.).
    *
    * Returns the literal discriminator string or `None` if neither
    * pattern matches.
    */
  private def discriminatorOf(schema: RawSchema): Option[String] = {
    val flat = flattenAllOf(schema, visited = Set.empty)
    flat.properties.get("type").flatMap { typeProp =>
      // Shape 1: $ref to a component.
      typeProp.ref.flatMap { ref =>
        resolveRef(ref).flatMap(singleStringEnumValue)
      }.orElse {
        // Shape 2: inline string-enum schema on the `type` property.
        if (typeProp.`type`.contains("string")) singleStringEnumValue(typeProp)
        else None
      }
    }
  }

  /** Returns the sole string value of a schema's `enum` if it
    * declares exactly one string-typed value, otherwise `None`.
    * Used by [[discriminatorOf]] for both `$ref` and inline shapes.
    */
  private def singleStringEnumValue(s: RawSchema): Option[String] = {
    if (s.enumValues.kind == EnumValues.Kind.Strings) s.enumValues.asStrings match {
      case only :: Nil => Some(only)
      case _ => None
    }
    else None
  }

  /** Register a synthesised resource union in the type buffer.
    *
    * @return the final (possibly suffixed) name actually registered
    */
  private def synthesizeResourceUnion(
    requestedName: String,
    variants: List[ResourceVariant],
    doc: Option[String],
    parent: Option[String],
    shortName: Option[String]
  ): String = {
    val name = uniqueSyntheticName(requestedName)
    val (effParent, effShort) =
      if (name == requestedName) (parent, shortName) else (None, None)
    if (!synthesized.exists(_.name == name))
      synthesized += TypeDef.ResourceUnion(name, variants, doc, effParent, effShort)
    name
  }

  /** Derive a preferred shared name for a merged-oneOf record from
    * its variant names. Two heuristics, tried in order:
    *
    *   - If every variant ends with the same non-trivial suffix
    *     (≥3 chars and shorter than each variant's full name), use
    *     `Any<Suffix>` — e.g. `[Timeframe, CustomTimeframe]` →
    *     `AnyTimeframe`, `[EmailSendOptions, SMSSendOptions,
    *     PushSendOptions]` → `AnySendOptions`.
    *
    *   - Otherwise join the variants with `Or` (e.g. `FooOrBar`) so
    *     long as the result stays within
    *     [[MaxMergedOneOfNameLength]]. For larger unions the join
    *     gets unwieldy, so this returns the joined form even past
    *     the cap — the caller's parent-scoped fallback (see
    *     [[synthesize]]) takes over when the joined form would be
    *     used and is overly long.
    *
    * Uniqueness against the rest of the type space is enforced at
    * registration time by [[pickSyntheticName]] inside [[synthesize]],
    * which falls back to a parent-scoped name (`<Parent><Field>`) if
    * the preferred LCS/joined form is already taken.
    */
  private def mergedOneOfName(variantNames: List[String]): String = {
    val lcs = longestCommonSuffix(variantNames)
    if (lcs.length >= 3 && variantNames.forall(_.length > lcs.length))
      s"Any${capitalize(lcs)}"
    else variantNames.mkString("Or")
  }

  /** Soft cap referenced by callers that want to detect "joined
    * variant list is getting unwieldy" and route to the parent-scoped
    * fallback. Kept around for callers / tests; not consulted inside
    * [[mergedOneOfName]] itself any more.
    */
  private val MaxMergedOneOfNameLength: Int = 80

  /** Longest common SUFFIX of a non-empty list of strings. Used by
    * [[mergedOneOfName]] to collapse `[FooBar, BazBar]` to `Bar`.
    */
  private def longestCommonSuffix(words: List[String]): String = {
    if (words.isEmpty) return ""
    val minLen = words.map(_.length).min
    var i = 0
    while (i < minLen && words.forall(w => w.charAt(w.length - 1 - i) == words.head.charAt(words.head.length - 1 - i)))
      i += 1
    words.head.substring(words.head.length - i)
  }

  /** Merge a `oneOf` array of `$ref`s into a single object schema.
    *
    * Used for Klaviyo's polymorphic-without-typed-discriminator unions
    * (`send_options`, `send_strategy`, `tracking_options`, segment
    * filter sets, …). Returns `None` if any item is not a clean ref
    * — the caller falls through to the generic handling.
    *
    * Merge rules:
    *   - properties are the union, preserving first-seen insertion
    *     order.
    *   - where two variants declare a property with the same name
    *     AND both schemas are inline string-enums, the merged
    *     property's enum is the UNION of all variants' enum values.
    *     This keeps the decoded record validatable for any variant
    *     (e.g. on `ProfileMetricPropertyFilter.filter`, the merged
    *     `type` enum becomes `["string","existence","boolean", …]`
    *     so any wire shape decodes successfully).
    *   - non-enum shape clashes keep the first variant's schema —
    *     mismatches there are rare in Klaviyo and the lossy default
    *     is acceptable until a regression surfaces.
    *   - `required` is the intersection: a field is required on the
    *     merged record only if every variant requires it.
    *   - description of the first variant is carried through; the
    *     parent property's own `description` (when present) overrides
    *     this at the call site in `toScalaType`.
    */
  private def mergedOneOfSchema(items: List[RawSchema]): Option[RawSchema] = {
    if (items.isEmpty) return None
    val resolved: List[Option[RawSchema]] = items.map { item =>
      item.ref.flatMap { ref =>
        val target = ref.stripPrefix("#/components/schemas/")
        if (target.contains('/')) None
        else resolveRef(ref).map(flattenAllOf(_, visited = Set.empty))
      }
    }
    if (resolved.exists(_.isEmpty)) return None
    val variants = resolved.flatten

    val merged = scala.collection.mutable.LinkedHashMap.empty[String, RawSchema]
    variants.foreach { v =>
      v.properties.foreach { case (k, schema) =>
        merged.get(k) match {
          case None => merged(k) = schema
          case Some(existing) => merged(k) = mergeProperty(existing, schema)
        }
      }
    }

    val universalRequired =
      variants.foldLeft(variants.head.required.toSet)((acc, v) => acc & v.required.toSet)

    Some(
      RawSchema(
        `type` = Some("object"),
        properties = ListMap(merged.toSeq: _*),
        required = merged.keys.toList.filter(universalRequired.contains),
        description = variants.iterator.flatMap(_.description).find(_ => true)
      )
    )
  }

  /** Per-property merge rule used by [[mergedOneOfSchema]] when the
    * same property name appears in multiple variants.
    *
    *   - both inline string-enums → union the enum values (keeps the
    *     decoded record valid for every variant's wire shape);
    *   - schemas disagree structurally AND at least one side carries
    *     a `$ref` or a nested `oneOf` → fold both sides into a
    *     synthetic `oneOf` containing every distinct ref/oneOf
    *     member. The planner then re-enters its oneOf handling for
    *     this synthetic shape, recursively producing the appropriate
    *     primitive-union / resource-union / merged-record type that
    *     covers every variant's intent;
    *   - everything else → first-seen wins (the lossy default).
    *
    * This recursive promotion is what keeps fields like
    * `AnyCondition.filter` from collapsing to the first variant's
    * specific filter type (e.g. `AnyThanPositiveNumericFilter`) when
    * other condition variants point `filter` at completely different
    * filter shapes — without it, a wire payload of a string filter
    * arriving at a `filter`-typed-as-NumericOperator field would
    * fail to decode.
    */
  private def mergeProperty(a: RawSchema, b: RawSchema): RawSchema = {
    val bothStringEnum =
      a.`type`.contains("string") && b.`type`.contains("string") &&
        a.enumValues.kind == EnumValues.Kind.Strings &&
        b.enumValues.kind == EnumValues.Kind.Strings &&
        a.enumValues.nonEmpty && b.enumValues.nonEmpty
    if (bothStringEnum) {
      val unionVals = (a.enumValues.asStrings ++ b.enumValues.asStrings).distinct
      a.copy(enumValues = EnumValues(unionVals, EnumValues.Kind.Strings))
    } else if (a != b && refOrOneOf(a) && refOrOneOf(b)) {
      // Both sides carry a ref/oneOf; collect every contributing
      // member and re-emit as a `oneOf` schema. De-dup by `$ref`
      // target where possible so a variant `A` and a variant
      // `oneOf: [A, B]` don't double up `A`.
      val items = (collectOneOfMembers(a) ++ collectOneOfMembers(b)).distinct
      if (items.lengthCompare(1) > 0) RawSchema(oneOf = items)
      else a
    } else a
  }

  /** True if `s` is a `$ref` to a component schema, OR carries a
    * non-empty `oneOf` array. These are the schemas
    * [[mergeProperty]] knows how to fold recursively.
    */
  private def refOrOneOf(s: RawSchema): Boolean =
    s.ref.isDefined || s.oneOf.nonEmpty

  /** Flatten a schema into the list of `oneOf` members it
    * contributes:
    *
    *   - a `$ref`-shaped schema contributes itself (one member);
    *   - a `oneOf` schema contributes each of its members verbatim;
    *   - anything else contributes itself (so a structurally-inline
    *     variant can still join a synthetic union — the recursive
    *     merge / planner walk will handle it).
    */
  private def collectOneOfMembers(s: RawSchema): List[RawSchema] = {
    if (s.oneOf.nonEmpty) s.oneOf
    else List(s)
  }

  /** Merge two schemas that target the same property name within an
    * `allOf` composition. Used by [[flattenAllOf]] for deep merging
    * when both items contribute a value for the same key.
    *
    * Semantics:
    *   - If either side carries `$ref` (pointing at a named shared
    *     type) we keep `b` verbatim — overriding with a refined
    *     in-place schema is rare and would lose the type identity.
    *   - Otherwise we recurse into `properties` and union `required`
    *     so a parent that declares `attributes.email` and a sibling
    *     that declares `attributes.subscriptions` produces an
    *     `attributes` containing both.
    *   - Non-object schemas just take `b` (the later one) — type
    *     refinement isn't something we model.
    *
    * No need to handle `allOf` inside the values here — both `a` and
    * `b` are pre-flattened by their callers via [[flattenAllOf]].
    */
  private def mergeSchemas(a: RawSchema, b: RawSchema): RawSchema = {
    if (a.ref.isDefined || b.ref.isDefined) b
    else if (a.properties.isEmpty && b.properties.isEmpty) b
    else {
      val merged = b.properties.foldLeft(a.properties) { case (acc, (k, v)) =>
        acc.get(k) match {
          case Some(existing) => acc + (k -> mergeSchemas(existing, v))
          case None => acc + (k -> v)
        }
      }
      a.copy(
        properties = merged,
        required = (a.required ++ b.required).distinct,
        `type` = a.`type`.orElse(b.`type`),
        description = a.description.orElse(b.description),
        nullable = a.nullable.orElse(b.nullable)
      )
    }
  }

  /** Add a synthetic record to the plan, recursing into its own field
    * types. De-duplicates among already-synthesised records by name;
    * if the requested name collides with a top-level component
    * schema, picks the next free suffix (`FooBar`, `FooBar2`, …).
    *
    * @return the final (possibly suffixed) name the record was
    *         registered under — callers must use this when emitting
    *         a Ref to the synthesised type, not the requested name.
    */
  private def synthesize(
    requestedName: String,
    schema: RawSchema,
    parent: Option[String] = None,
    shortName: Option[String] = None,
    fallbackName: Option[String] = None
  ): String = {
    val name = pickSyntheticName(requestedName, fallbackName, allowReuse = true)
    // If the name had to be disambiguated to a fallback or a numeric
    // suffix, the parent-prefix contract is broken — drop the
    // nesting metadata rather than mislead the emitter.
    val (effectiveParent, effectiveShort) =
      if (name == requestedName) (parent, shortName) else (None, None)
    if (!synthesized.exists(_.name == name)) {
      // Reserve the name first so a self-referential schema does not
      // recurse forever before being added to the buffer.
      val placeholder = TypeDef.Record(
        name = name,
        fields = Nil,
        doc = schema.description,
        parent = effectiveParent,
        shortName = effectiveShort
      )
      synthesized += placeholder
      val real = toTypeDef(name, schema, effectiveParent, effectiveShort)
      val idx = synthesized.indexWhere(_.name == name)
      synthesized.update(idx, real)
    }
    name
  }

  /** Returns a name guaranteed not to collide with a top-level
    * component schema. Already-synthesised types with the same
    * requested name are treated as reuse (the same parent + field
    * combo legitimately points at the same synthetic), but a clash
    * with a `components.schemas` entry forces disambiguation.
    */
  private def uniqueSyntheticName(requested: String): String = {
    def clashesWithComponent(n: String): Boolean = raw.components.schemas.contains(n)
    if (!clashesWithComponent(requested)) requested
    else {
      var i = 2
      def taken(n: String): Boolean = clashesWithComponent(n) || synthesized.exists(_.name == n)
      while (taken(s"$requested$i")) i += 1
      s"$requested$i"
    }
  }

  /** Pick a synthetic type name, preferring `preferred`, then
    * `fallback` (if distinct), then a numeric suffix on `preferred`.
    *
    * `allowReuse = true` (the default for [[synthesize]] on records)
    * means an existing synthesised type with the matching name is
    * treated as a reuse-target rather than a collision — the caller
    * already determined that the structural shape matches.
    *
    * `allowReuse = false` (used by [[synthesizeStringEnum]]) means a
    * name clash forces disambiguation, because two enums with the
    * same `<FieldName>Enum` requested name may carry different value
    * sets — letting them collapse would silently reshape one of them.
    */
  private def pickSyntheticName(preferred: String, fallback: Option[String], allowReuse: Boolean): String = {
    def isComponent(n: String): Boolean = raw.components.schemas.contains(n)
    def isSynth(n: String): Boolean = synthesized.exists(_.name == n)
    def reuseOk(n: String): Boolean = allowReuse && isSynth(n) && !isComponent(n)
    def clash(n: String): Boolean = isComponent(n) || (!allowReuse && isSynth(n))
    if (reuseOk(preferred) || !clash(preferred)) preferred
    else fallback match {
      case Some(fb) if fb != preferred && (reuseOk(fb) || !clash(fb)) => fb
      case _ =>
        var i = 2
        while (clash(s"${preferred}_$i")) i += 1
        s"${preferred}_$i"
    }
  }

  /** Render a spec-supplied `default` value to Scala source text,
    * pre-wrapped for the field's Tristate shape when the field isn't
    * a bare `T`.
    *
    * Returns `None` for shapes the codegen doesn't have a clean
    * Scala literal for (free-form objects/arrays, numeric defaults
    * for a field typed as `String`, etc.). The emitter then falls
    * back to its built-in default policy.
    */
  private def renderDefault(
    raw: RawDefault,
    scalaType: ScalaType,
    required: Boolean,
    nullable: Boolean
  ): Option[String] = {
    def lit(text: String, valid: Boolean): Option[String] = if (valid) Some(text) else None
    val literal: Option[String] = raw match {
      case RawDefault.Str(s) =>
        scalaType match {
          case ScalaType.Str => Some("\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
          case _: ScalaType.Ref => None // can't safely fabricate an enum instance from a bare string
          case _ => None
        }
      case RawDefault.Bool(b) => lit(b.toString, scalaType == ScalaType.Bool)
      case RawDefault.I(v) =>
        scalaType match {
          case ScalaType.I32 => Some(v.toInt.toString)
          case ScalaType.I64 => Some(v.toString + "L")
          case ScalaType.Dbl => Some(v.toString + ".0")
          case _ => None
        }
      case RawDefault.D(v) => lit(v.toString, scalaType == ScalaType.Dbl)
      case RawDefault.Null => Some("null")
      case RawDefault.Skipped => None
    }
    literal.map { lit =>
      raw match {
        case RawDefault.Null =>
          // `null` is only representable as a wire-format default on
          // a Tristate state that admits it: `Nullable[T]` (required+
          // nullable). For an `!required` field (`Optional` / `Maybe`)
          // the case-class default must remain `Tristate.Absent` —
          // see the non-null branch below — so the spec's null
          // default just folds into the omitted-field semantics:
          // omit on the wire, server applies its own default.
          if (required && nullable) "com.alexdupre.klaviyo.core.Tristate.Null"
          else "" // sentinel below
        case _ =>
          // Required + non-nullable: bare `T` type, use the literal
          // as-is.
          //
          // Required + nullable: `Tristate.Nullable[T]` type, which
          // admits `Null | Value` (never `Absent`). Wrapping the
          // literal in `Tristate.Value(...)` is safe — `Absent` is
          // unreachable, so the codec's "transientDefault collides
          // with Absent" trap can't trigger.
          //
          // !required (Optional or Maybe): `Tristate.Absent` is
          // reachable, so we MUST NOT use `Tristate.Value(default)`
          // as the case-class default. The trap is:
          //   - Field default in plan: `Tristate.Value(false)`.
          //   - User passes `Tristate.Absent` (e.g. via explicit
          //     omission intent).
          //   - jsoniter's `transientDefault=true` sees
          //     `Absent != Value(false)` → emits the field on the
          //     wire → calls Tristate codec's `encodeValue(Absent)`
          //     → throws `Tristate.Absent must not reach
          //     encodeValue …`.
          // Returning `""` here (filtered out by `.filter(_.nonEmpty)`
          // below) hands off to the emitter's default policy, which
          // uses `Tristate.Absent`. The server-side default still
          // applies whenever the field is omitted, so the wire
          // behaviour for "user didn't set the field" is unchanged.
          if (required && !nullable) lit
          else if (required && nullable) s"com.alexdupre.klaviyo.core.Tristate.Value($lit)"
          else "" // !required → defer to emitter's `Tristate.Absent` fallback
      }
    }.filter(_.nonEmpty)
  }

  /** Register a string enum synthesised from an inline schema, with
    * content-based deduplication.
    *
    * Two callers passing the same value set (irrespective of
    * `requestedName`) get the same synthetic type — we only register
    * the first and return its name for every subsequent identical
    * value set. This collapses what was previously hundreds of
    * structurally identical synthetic enums (one per parent/field
    * pair) into a single shared type per unique value set.
    *
    * Names are assigned eagerly so the caller can produce a [[ScalaType.Ref]],
    * but the assignment is provisional: every call also records the
    * requesting parent and the preferred short form in
    * [[inlineEnumContext]]. [[renameInlineEnums]] runs after the
    * whole plan is built and reshuffles names based on the global
    * picture — see its docstring for the policy.
    */
  private def synthesizeStringEnum(
    preferred: String,
    parentScoped: String,
    parentName: String,
    fieldPascal: String,
    values: List[String],
    doc: Option[String]
  ): String = {
    val key = values.sorted
    // Track this requester whether or not we end up creating a new
    // type — the rename pass needs every parent that references the
    // value set, not just the first.
    inlineEnumContext.get(key) match {
      case Some(ctx) => ctx.parents += parentName
      case None =>
        inlineEnumContext(key) = Planner.InlineEnumContext(
          preferred = preferred,
          fieldPascal = fieldPascal,
          parents = scala.collection.mutable.Set(parentName)
        )
    }
    inlineEnumByValues.get(key) match {
      case Some(existing) => existing
      case None =>
        val name = pickSyntheticName(preferred, Some(parentScoped), allowReuse = false)
        synthesized += TypeDef.StringEnum(name, values, doc)
        inlineEnumByValues(key) = name
        name
    }
  }

  /** Reserved keywords that cannot appear unquoted as Scala identifiers.
    * Backticked versions are syntactically valid but ugly; we prefer to
    * keep the keyword and let the emitter backtick at the use site.
    */
  private val reservedWords: Set[String] = Set(
    "type",
    "class",
    "object",
    "trait",
    "def",
    "val",
    "var",
    "if",
    "else",
    "match",
    "case",
    "this",
    "super",
    "null",
    "true",
    "false",
    "package",
    "import",
    "extends",
    "with",
    "for",
    "while",
    "do",
    "return",
    "yield",
    "new",
    "throw",
    "try",
    "catch",
    "finally",
    "given",
    "using",
    "then",
    "private",
    "protected",
    "abstract",
    "final",
    "sealed",
    "lazy",
    "override",
    "implicit",
    "inline",
    "transparent",
    "opaque",
    "open",
    "forSome",
    "macro",
    "enum"
  )

  /** Convert a wire-format identifier (snake_case, possibly bracketed)
    * into a Scala-legal identifier.
    *
    * `fields[account]` → `fieldsAccount`
    * `page[cursor]`    → `pageCursor`
    * `revision`        → `revision`
    * `type`            → `` `type` ``
    */
  private[model] def toScalaIdentifier(jsonName: String): String = {
    // Replace bracket pairs and hyphens with underscores so the
    // camelCaser groups them as separate words. Hyphens appear in
    // Klaviyo field names like `webhook-topics`; without this the
    // emitter would produce `webhook-topics` verbatim which Scala
    // would (mis)parse as a subtraction.
    val noBrackets = jsonName.replace("[", "_").replace("]", "")
    val camel = toCamelCase(noBrackets)
    if (reservedWords.contains(camel)) s"`$camel`" else camel
  }

  /** snake_case (or kebab-case) → camelCase. Empty input returns empty.
    * Splits on both `_` and `-` to handle both spellings.
    */
  private[model] def toCamelCase(s: String): String = {
    val parts = s.split("[_\\-]").filter(_.nonEmpty)
    if (parts.isEmpty) s
    else parts.head + parts.tail.map(capitalize).mkString
  }

  private[model] def capitalize(s: String): String =
    if (s.isEmpty) s else s.head.toUpper +: s.tail

  /** snake_case or kebab-case → PascalCase. Used for synthetic type
    * names (which must be valid Scala class identifiers).
    */
  /** Render an arbitrary string as a PascalCase Scala identifier
    * (no leading-digit guard — callers prefix a parent name).
    *
    * Splits on every non-alphanumeric character so spec-supplied
    * names like `fields[account]` or `page[cursor]` produce
    * `FieldsAccount` / `PageCursor` rather than retaining brackets
    * that would make the synthesised type name unparseable.
    */
  private[model] def pascalCase(s: String): String = {
    val parts = s.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
    if (parts.isEmpty) ""
    else parts.map(capitalize).mkString
  }
}

object Planner {

  /** Per-value-set metadata used by the post-plan rename pass to
    * pick truthful names. `parents` accumulates across calls so that
    * by end-of-plan we know every requester. `fieldPascal` is the
    * PascalCased field name (`"Status"`, `"Method"`, …); `preferred`
    * is the short `<Field>Enum` form. Filled in by every call to
    * `synthesizeStringEnum` regardless of whether the value set is
    * new or being reused.
    *
    * Declared in the companion (not nested in the `Planner` class)
    * so Scala 2.12 doesn't emit a runtime outer-reference check on
    * pattern matches that destructure it.
    */
  private final case class InlineEnumContext(
    preferred: String,
    fieldPascal: String,
    parents: scala.collection.mutable.Set[String]
  )

  /** Per-variant-set tracking for merged-oneOf records. The
    * `fieldPascal` is the requesting field name in PascalCase (used
    * as the nested-companion short name); `parents` is the set of
    * parent records that reference this variant set.
    *
    * Declared in the companion to avoid Scala 2.12's outer-reference
    * check on pattern matches that destructure it.
    */
  private final case class MergedOneOfContext(
    fieldPascal: String,
    parents: scala.collection.mutable.Set[String]
  )

  /** Plan with no restriction — emits every category. */
  def plan(raw: RawSpec): SpecPlan = new Planner(raw).plan

  /** Plan restricted to the union of the given operation tags and
    * operationIds. An operation is kept iff its tag is in
    * `selectedTags` OR its `operationId` is in `selectedOperationIds`.
    *
    * Both arguments default to the empty set; when both are empty
    * the planner emits every operation, matching the single-arg
    * `plan` overload. Unknown names are tolerated silently (callers
    * wanting strict matching should diff against the spec themselves).
    */
  def plan(
    raw: RawSpec,
    selectedTags: Set[String] = Set.empty,
    selectedOperationIds: Set[String] = Set.empty
  ): SpecPlan =
    new Planner(raw, selectedTags, selectedOperationIds).plan
}
