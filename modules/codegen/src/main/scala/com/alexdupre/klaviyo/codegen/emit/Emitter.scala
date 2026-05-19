package com.alexdupre.klaviyo.codegen.emit

import com.alexdupre.klaviyo.codegen.model._

import scala.meta._
import scala.meta.dialects.Scala3

/** Walks a [[SpecPlan]] and emits the full source tree for the
  * `klaviyo4s` artefact:
  *
  *   - `models/<TypeName>.scala` — one file per DTO, flat under
  *     `com.alexdupre.klaviyo.models`. No per-category duplication.
  *   - `<category>/<Category>Api.scala` — one file per category, the
  *     generated method-set for that category's operations.
  *   - `<category>/Extensions.scala` — the `extension … def <category>`
  *     wiring onto `KlaviyoClient[F]`.
  *   - `package.scala` — the top-level re-export bundle so
  *     `import com.alexdupre.klaviyo.*` is enough for the
  *     runtime essentials plus every category's accessor.
  *
  * Implementation note:
  *
  * The emitter uses string templates with scala.meta as a validator —
  * each generated source is parsed with the Scala 3 dialect before
  * being returned, so a malformed template fails fast at codegen time
  * with a clear parse error pointing at the offending line. Templates
  * are dramatically more readable for top-down source generation
  * while still providing parse-time validation on the output.
  *
  * Scalafmt is NOT run inside the codegen; the output is correct Scala
  * but its whitespace is whatever the templates produce. `sbt
  * klaviyo4sFormatGenerated` (or running scalafmt over the output
  * directory directly) canonicalises afterwards.
  */
/** Companion façade for [[Emitter]]. Construct an emitter with the
  * configured base package and delegate `emit`. The default base
  * package (`com.alexdupre.klaviyo`) matches what the published
  * runtime artefact uses; the sbt plugin overrides this when the
  * user wants generated sources under a different root.
  */
object Emitter {

  def emit(plan: SpecPlan, basePackage: String = "com.alexdupre.klaviyo"): Seq[EmittedFile] =
    new Emitter(basePackage).emit(plan)
}

class Emitter(val basePackage: String) {

  /** Root package for everything the codegen emits. */
  private val RootPkg: String = basePackage

  /** Where all DTOs live. */
  private val ModelsPkg: String = s"$RootPkg.models"

  /** Where per-category Api classes live. The extra `api` segment is
    * deliberate: it avoids a name collision between the package name
    * (`com.alexdupre.klaviyo.<category>`) and the extension method
    * with the same name (`def <category>: <Category>Api`). Scala 3
    * does not allow a top-level extension method to share a name
    * with a sibling package.
    */
  private val ApiPkg: String = s"$RootPkg.api"

  /** Display-name map populated by [[emit]]: full plan-level name
    * (`"AccountResponseObjectResourceAttributes"`) → rendered Scala
    * name (`"AccountResponseObjectResource.Attributes"`). Plain
    * top-level types map to themselves. Used by [[renderScalaType]]
    * to produce dotted access for nested records.
    */
  private var displayNameMap: Map[String, String] = Map.empty

  /** Records grouped by parent name. Populated by [[emit]] and
    * consumed by [[renderRecord]] when rendering a parent's
    * companion body. Children are emitted in plan order (their
    * synthesis order, which roughly tracks declaration order in
    * the spec).
    */
  private var childRecordsByParent: Map[String, List[TypeDef.Record]] = Map.empty

  /** StringEnums grouped by parent name. Same shape as
    * [[childRecordsByParent]] but for inline enums — single-parent
    * inline enums get nested in their parent's companion alongside
    * any record children.
    */
  private var childEnumsByParent: Map[String, List[TypeDef.StringEnum]] = Map.empty

  /** PrimitiveUnions grouped by parent name. Primitive-of-oneOf
    * types synthesised at a field position are nested under that
    * field's parent record (they're always single-parent —
    * different parents wanting the same shape would hit different
    * `<parent><field>` prefixes).
    */
  private var childPrimitiveUnionsByParent: Map[String, List[TypeDef.PrimitiveUnion]] = Map.empty

  /** ResourceUnions grouped by parent name. Same shape as
    * [[childPrimitiveUnionsByParent]]; covers `oneOf` of
    * JSON:API-style discriminated resources.
    */
  private var childResourceUnionsByParent: Map[String, List[TypeDef.ResourceUnion]] = Map.empty

  /** Records indexed by full plan-level name. Used by the API method
    * emitter to look up the body shape's field set when assembling a
    * multipart request — we need to walk the case-class fields to
    * decide which sttp helper to use per part (`multipartFile` for a
    * `java.nio.file.Path` field, `multipart` for everything else).
    */
  private var recordsByName: Map[String, TypeDef.Record] = Map.empty

  /** Emit every file for the whole spec. */
  def emit(plan: SpecPlan): Seq[EmittedFile] = {
    val records = plan.types.collect { case r: TypeDef.Record => r }
    val enums = plan.types.collect { case e: TypeDef.StringEnum => e }
    val primUnions = plan.types.collect { case u: TypeDef.PrimitiveUnion => u }
    val resUnions = plan.types.collect { case u: TypeDef.ResourceUnion => u }
    childRecordsByParent = records.filter(_.parent.isDefined).groupBy(_.parent.get)
    childEnumsByParent = enums.filter(_.parent.isDefined).groupBy(_.parent.get)
    childPrimitiveUnionsByParent = primUnions.filter(_.parent.isDefined).groupBy(_.parent.get)
    childResourceUnionsByParent = resUnions.filter(_.parent.isDefined).groupBy(_.parent.get)
    recordsByName = records.iterator.map(r => r.name -> r).toMap
    displayNameMap = buildDisplayNameMap(plan.types, records, enums, primUnions, resUnions)

    // Every nestable type — records, inline enums, primitive
    // unions, resource unions — is inlined into its parent's
    // emitted file when `parent` is set. Top-level types keep their
    // own files.
    val topLevelTypes = plan.types.filter {
      case r: TypeDef.Record => r.parent.isEmpty
      case e: TypeDef.StringEnum => e.parent.isEmpty
      case u: TypeDef.PrimitiveUnion => u.parent.isEmpty
      case u: TypeDef.ResourceUnion => u.parent.isEmpty
    }
    val typeFiles = topLevelTypes.map(emitTypeDef)
    val categoryFiles = plan.categories.flatMap { cat =>
      val filterFiles = cat.operations.flatMap(op => emitFilter(cat, op))
      Seq(emitApi(cat), emitExtensions(cat)) ++ filterFiles
    }
    val rootPkgFile = emitRootPackage(plan.categories, plan.revision)
    typeFiles ++ categoryFiles :+ rootPkgFile
  }

  /** Walk the type list and produce, for every name, the dotted form
    * a Scala source file should reference it by. Nested records and
    * nested enums walk their parent chain so a child of a child
    * renders as `Outer.Middle.Inner`.
    */
  private def buildDisplayNameMap(
    types: List[TypeDef],
    records: List[TypeDef.Record],
    enums: List[TypeDef.StringEnum],
    primUnions: List[TypeDef.PrimitiveUnion],
    resUnions: List[TypeDef.ResourceUnion]
  ): Map[String, String] = {
    val recordByName = records.iterator.map(r => r.name -> r).toMap
    val enumByName = enums.iterator.map(e => e.name -> e).toMap
    val primByName = primUnions.iterator.map(u => u.name -> u).toMap
    val resByName = resUnions.iterator.map(u => u.name -> u).toMap
    def nestedDotted(parent: String, shortName: String): String =
      s"${resolve(parent)}.$shortName"
    def resolve(name: String): String = {
      recordByName.get(name).flatMap(r =>
        r.parent.map(p => nestedDotted(p, r.shortName.getOrElse(name.stripPrefix(p))))
      )
        .orElse(enumByName.get(name).flatMap(e =>
          e.parent.map(p => nestedDotted(p, e.shortName.getOrElse(name.stripPrefix(p))))
        ))
        .orElse(primByName.get(name).flatMap(u =>
          u.parent.map(p => nestedDotted(p, u.shortName.getOrElse(name.stripPrefix(p))))
        ))
        .orElse(resByName.get(name).flatMap(u =>
          u.parent.map(p => nestedDotted(p, u.shortName.getOrElse(name.stripPrefix(p))))
        ))
        .getOrElse(name)
    }
    types.iterator.map(t => t.name -> resolve(t.name)).toMap
  }

  /** Apply [[displayNameMap]] to a single plan-level name. */
  private def displayName(name: String): String =
    displayNameMap.getOrElse(name, name)

  // ------------------------------------------------------------------
  // Root package re-exports
  // ------------------------------------------------------------------

  /** Top-level `package.scala` whose `export`s make
    * `import com.alexdupre.klaviyo.*` enough for the runtime
    * essentials and every category's extension methods. Adding a
    * category in a future spec refresh produces a new `export` line
    * here automatically — no manual edit needed.
    *
    * Also emits `GeneratedSpecRevision`, the `info.version` from the
    * spec used to generate this code. `KlaviyoConfig.revision` no
    * longer has a default, so user code should pass this constant —
    * that way the runtime `revision` header always matches the
    * shapes the generated types target.
    */
  private def emitRootPackage(categories: List[CategoryPlan], specRevision: String): EmittedFile = {
    val categoryExports = categories
      .map(c => s"export $ApiPkg.${c.packageName}.Extensions.*")
      .mkString("\n")

    // Scala 3 allows top-level definitions in a package — the file
    // contains a package declaration, the header comment, the
    // generated-revision constant, then the exports.
    val template =
      s"""|package $RootPkg
          |
          |${header}
          |
          |// --- Spec revision --------------------------------------------------------
          |
          |/** The Klaviyo API revision (`info.version` in `stable.json`)
          |  * that this code was generated against. Pass to
          |  * `KlaviyoConfig(revision = GeneratedSpecRevision)` so the
          |  * `revision` header on every request matches the shapes
          |  * the generated DTOs encode/decode.
          |  *
          |  * Regenerating against a newer spec replaces this constant
          |  * automatically.
          |  */
          |val GeneratedSpecRevision: String = "${escapeStr(specRevision)}"
          |
          |// --- Runtime essentials ---------------------------------------------------
          |
          |export com.alexdupre.klaviyo.core.{
          |  KlaviyoAuth,
          |  KlaviyoConfig,
          |  KlaviyoError,
          |  RetryPolicy,
          |  Tristate,
          |  Cursor,
          |  Page,
          |  Filter,
          |  RawJson
          |}
          |
          |export com.alexdupre.klaviyo.core.{KlaviyoClient, Sleep, Pagination}
          |export com.alexdupre.klaviyo.core.KlaviyoClientPaginationOps.*
          |
          |// --- Per-category extensions ----------------------------------------------
          |
          |$categoryExports
          |""".stripMargin

    EmittedFile("package.scala", validate(template))
  }

  // ------------------------------------------------------------------
  // Type definitions: enums, records, primitive/resource unions
  // ------------------------------------------------------------------

  private def emitTypeDef(td: TypeDef): EmittedFile = td match {
    case e: TypeDef.StringEnum => emitEnum(e)
    case r: TypeDef.Record => emitRecord(r)
    case u: TypeDef.PrimitiveUnion => emitPrimitiveUnion(u)
    case u: TypeDef.ResourceUnion => emitResourceUnion(u)
  }

  /** A sealed trait over JSON:API resource variants, discriminated by
    * the wire `type` field. Each variant wraps a generated case
    * class; the codec uses `setMark`/`rollbackToMark` to peek the
    * `type` value without consuming the object, then delegates the
    * real decode to the matching inner codec.
    */
  private def emitResourceUnion(td: TypeDef.ResourceUnion): EmittedFile = {
    val template =
      s"""|package $ModelsPkg
          |
          |${scaladoc(td.doc)}${header}
          |
          |import com.github.plokhotnyuk.jsoniter_scala.core.*
          |import com.github.plokhotnyuk.jsoniter_scala.macros.{JsonCodecMaker, CodecMakerConfig}
          |
          |${renderResourceUnion(td)}
          |""".stripMargin
    EmittedFile(s"models/${td.name}.scala", validate(template))
  }

  /** Produce the Scala source for `sealed trait X` + companion
    * carrying the JSON:API-discriminator codec. Used both for the
    * top-level file emit ([[emitResourceUnion]]) and for inlining
    * into a parent's companion ([[renderRecord]] picks up nested
    * union children alongside nested records and enums).
    */
  private def renderResourceUnion(td: TypeDef.ResourceUnion): String = {
    val localName = td.shortName.getOrElse(td.name)
    val cases = td.variants
      .map { v =>
        val inner = renderScalaType(v.scalaType)
        s"  final case class ${v.caseName}(value: $inner) extends $localName"
      }
      .mkString("\n")
    val decodeCases = td.variants
      .map { v =>
        val inner = renderScalaType(v.scalaType)
        s"""        case "${escapeStr(
            v.discriminator
          )}" => $localName.${v.caseName}(summon[JsonValueCodec[$inner]].decodeValue(in, null.asInstanceOf[$inner]))"""
      }
      .mkString("\n")
    val encodeCases = td.variants
      .map { v =>
        val inner = renderScalaType(v.scalaType)
        s"      case $localName.${v.caseName}(v) => summon[JsonValueCodec[$inner]].encodeValue(v, out)"
      }
      .mkString("\n")
    val firstVariant = td.variants.headOption.map(_.caseName).getOrElse("Unknown")
    val firstInner = td.variants.headOption.map(v => renderScalaType(v.scalaType)).getOrElse("Nothing")
    val docPrefix = if (td.parent.isDefined) scaladoc(td.doc) else ""

    s"""|${docPrefix}sealed trait $localName
        |
        |object $localName {
        |$cases
        |
        |  given JsonValueCodec[$localName] = new JsonValueCodec[$localName] {
        |    def decodeValue(in: JsonReader, default: $localName): $localName = {
        |      in.setMark()
        |      val discriminator = peekDiscriminator(in)
        |      in.rollbackToMark()
        |      discriminator match {
        |$decodeCases
        |        case other => in.decodeError("unknown `type` discriminator: " + other)
        |      }
        |    }
        |
        |    def encodeValue(x: $localName, out: JsonWriter): Unit = x match {
        |$encodeCases
        |    }
        |
        |    def nullValue: $localName = $localName.$firstVariant(null.asInstanceOf[$firstInner])
        |
        |    private def peekDiscriminator(in: JsonReader): String = {
        |      if (!in.isNextToken('{')) in.objectStartOrNullError()
        |      var typeVal: String = null
        |      if (!in.isNextToken('}')) {
        |        in.rollbackToken()
        |        var continue = true
        |        while (continue) {
        |          val k = in.readKeyAsString()
        |          if (k == "type" && typeVal == null) typeVal = in.readString(null)
        |          else in.skip()
        |          continue = in.isNextToken(',')
        |          if (!continue && !in.isCurrentToken('}')) in.objectEndOrCommaError()
        |        }
        |      }
        |      if (typeVal == null) in.decodeError("compound document item missing `type` field")
        |      typeVal
        |    }
        |  }
        |
        |  given JsonValueCodec[Vector[$localName]] = JsonCodecMaker.make(
        |    CodecMakerConfig.withAllowRecursiveTypes(true)
        |  )
        |}""".stripMargin
  }

  /** A sealed trait over a small set of primitive JSON variants. */
  private def emitPrimitiveUnion(td: TypeDef.PrimitiveUnion): EmittedFile = {
    val template =
      s"""|package $ModelsPkg
          |
          |${scaladoc(td.doc)}${header}
          |
          |import com.github.plokhotnyuk.jsoniter_scala.core.*
          |import com.github.plokhotnyuk.jsoniter_scala.macros.{JsonCodecMaker, CodecMakerConfig}
          |
          |${renderPrimitiveUnion(td)}
          |""".stripMargin
    EmittedFile(s"models/${td.name}.scala", validate(template))
  }

  /** Produce the Scala source for `sealed trait X` + companion with
    * a token-dispatching codec. Same render-vs-emit split as the
    * record / enum / resource-union helpers above; reused by
    * [[renderRecord]] when nesting a primitive-union child inside a
    * parent's companion.
    */
  private def renderPrimitiveUnion(td: TypeDef.PrimitiveUnion): String = {
    val localName = td.shortName.getOrElse(td.name)
    val cases = td.variants
      .map { v =>
        val inner = renderScalaType(v.scalaType)
        if (v.kind == PrimitiveKind.Null)
          s"  case object ${v.caseName} extends $localName"
        else
          s"  final case class ${v.caseName}(value: $inner) extends $localName"
      }
      .mkString("\n")
    val decodeBranches = td.variants
      .map { v =>
        val read = v.kind match {
          case PrimitiveKind.Str => "in.readString(null)"
          case PrimitiveKind.Num =>
            v.scalaType match {
              case ScalaType.I64 => "in.readLong()"
              case _ => "in.readDouble()"
            }
          case PrimitiveKind.Bool => "in.readBoolean()"
          case PrimitiveKind.Null => "{ in.readNullOrError(null, \"expected null\"); null }"
        }
        v.kind match {
          case PrimitiveKind.Null =>
            s"""      case 'n' => in.readNullOrError($localName.${v.caseName}, "expected null or value")"""
          case _ =>
            val pred = v.kind match {
              case PrimitiveKind.Str => "b == '\"'"
              case PrimitiveKind.Num => "(b >= '0' && b <= '9') || b == '-'"
              case PrimitiveKind.Bool => "b == 't' || b == 'f'"
              case PrimitiveKind.Null => "b == 'n'"
            }
            s"      case b if $pred => { in.rollbackToken(); $localName.${v.caseName}($read) }"
        }
      }
      .mkString("\n")
    val encodeBranches = td.variants
      .map { v =>
        v.kind match {
          case PrimitiveKind.Null =>
            s"      case $localName.${v.caseName}    => out.writeNull()"
          case _ =>
            s"      case $localName.${v.caseName}(value) => out.writeVal(value)"
        }
      }
      .mkString("\n")
    val firstCase = td.variants.headOption.map(_.caseName).getOrElse("Unknown")
    val nullSentinel = td.variants.headOption match {
      case Some(v) if v.kind == PrimitiveKind.Null => s"$localName.$firstCase"
      case Some(v) if v.scalaType == ScalaType.Str => s"""$localName.$firstCase("")"""
      case Some(v) if v.scalaType == ScalaType.I64 => s"$localName.$firstCase(0L)"
      case Some(v) if v.scalaType == ScalaType.I32 => s"$localName.$firstCase(0)"
      case Some(v) if v.scalaType == ScalaType.Dbl => s"$localName.$firstCase(0.0d)"
      case Some(v) if v.scalaType == ScalaType.Bool => s"$localName.$firstCase(false)"
      case _ => s"$localName.$firstCase(null.asInstanceOf[Nothing])"
    }
    val docPrefix = if (td.parent.isDefined) scaladoc(td.doc) else ""

    s"""|${docPrefix}sealed trait $localName
        |
        |object $localName {
        |$cases
        |
        |  given JsonValueCodec[$localName] = new JsonValueCodec[$localName] {
        |    def decodeValue(in: JsonReader, default: $localName): $localName = {
        |      val b = in.nextToken()
        |      b match {
        |$decodeBranches
        |        case _ => in.decodeError("expected primitive value for $localName")
        |      }
        |    }
        |    def encodeValue(x: $localName, out: JsonWriter): Unit = x match {
        |$encodeBranches
        |    }
        |    def nullValue: $localName = $nullSentinel
        |  }
        |
        |  given JsonValueCodec[Vector[$localName]] = JsonCodecMaker.make(
        |    CodecMakerConfig.withAllowRecursiveTypes(true)
        |  )
        |}""".stripMargin
  }

  /** A Scala 3 `enum` with a string-backed jsoniter codec. */
  private def emitEnum(td: TypeDef.StringEnum): EmittedFile = {
    // Top-level wrapper: package decl, header, the import jsoniter
    // needs, then the enum content. The body itself comes from
    // [[renderEnum]] so the nested-enum path (rendered inside a
    // parent's companion) reuses the same shape.
    val template =
      s"""|package $ModelsPkg
          |
          |${scaladoc(td.doc)}${header}
          |
          |import com.github.plokhotnyuk.jsoniter_scala.core.*
          |import com.github.plokhotnyuk.jsoniter_scala.macros.{JsonCodecMaker, CodecMakerConfig}
          |
          |${renderEnum(td)}
          |""".stripMargin

    EmittedFile(s"models/${td.name}.scala", validate(template))
  }

  /** Produce the Scala source for `enum X(val value: String) { … }`
    * plus its companion object (containing the codec + the `Vector`
    * codec), with no package declaration or imports. Reused at two
    * sites:
    *
    *   - [[emitEnum]] wraps it in a file template for top-level
    *     enums.
    *   - [[renderRecord]] inlines it into a parent companion when
    *     `td.parent.isDefined` — i.e. for single-parent inline
    *     enums that get nested alongside the parent's other
    *     children.
    *
    * Uses `td.shortName.getOrElse(td.name)` so the rendered Scala
    * names match the nesting context: nested enums use the short
    * `<Field>Enum` form; top-level enums use their full plan name.
    */
  private def renderEnum(td: TypeDef.StringEnum): String = {
    val localName = td.shortName.getOrElse(td.name)
    // Build (wire value, Scala case name) pairs. `enumCaseName`
    // returns a backtick-wrapped name when the value contains
    // `+`/`-`/`/` so opposing-sign pairs (`created_at` /
    // `-created_at`, `Etc/GMT+0` / `Etc/GMT-0`) stay distinct
    // without collapsing into ugly numeric suffixes. Any residual
    // collision — same PascalCase shape with no preserved special
    // chars to tell them apart, rare — falls back to a `_N`
    // disambiguation suffix as a last resort.
    val pairs: Seq[(String, String)] = {
      val counts = scala.collection.mutable.Map.empty[String, Int]
      td.values.map { v =>
        val base = enumCaseName(v)
        val n = counts.getOrElse(base, 0) + 1
        counts(base) = n
        val name =
          if (n == 1) base
          else if (base.startsWith("`") && base.endsWith("`"))
            "`" + base.stripPrefix("`").stripSuffix("`") + s"_$n`"
          else s"${base}_$n"
        v -> name
      }
    }
    val cases = pairs.map { case (v, n) => s"""  case $n extends $localName("${escapeStr(v)}")""" }.mkString("\n")
    val map = pairs.map { case (v, n) => s""""${escapeStr(v)}" -> $localName.$n""" }.mkString(", ")
    val firstCase = pairs.headOption.map(_._2).getOrElse("Unknown")
    val docPrefix = if (td.parent.isDefined) scaladoc(td.doc) else ""

    s"""|${docPrefix}enum $localName(val value: String) {
        |$cases
        |
        |  // Render as the wire value so generated query-param
        |  // encoders (which mkString a Vector of enum cases) emit
        |  // `public_api_key` instead of the Scala case name
        |  // `PublicApiKey`.
        |  override def toString: String = value
        |}
        |
        |object $localName {
        |
        |  private val byValue: Map[String, $localName] = Map($map)
        |
        |  given JsonValueCodec[$localName] = new JsonValueCodec[$localName] {
        |    def decodeValue(in: JsonReader, default: $localName): $localName = {
        |      val s = in.readString(null)
        |      byValue.getOrElse(s, in.decodeError("unknown $localName value: " + s))
        |    }
        |    def encodeValue(x: $localName, out: JsonWriter): Unit = out.writeVal(x.value)
        |    def nullValue: $localName = $localName.$firstCase
        |  }
        |
        |  given JsonValueCodec[Vector[$localName]] = JsonCodecMaker.make(
        |    CodecMakerConfig.withAllowRecursiveTypes(true)
        |  )
        |}""".stripMargin
  }

  /** A final case class with a jsoniter codec on its companion.
    *
    * Field-name mapping: jsoniter's global `enforce_snake_case`
    * field-name mapper handles camelCase Scala → snake_case JSON.
    * The emitter adds a `@named("...")` only when the auto-mapping
    * would not match the wire name (e.g. names with hyphens or
    * brackets — which don't appear in bodies but the policy stays
    * robust against future quirks).
    */
  private def emitRecord(td: TypeDef.Record): EmittedFile = {
    // Top-level wrapper: package decl, shared imports, then the
    // record content. The content (case class + companion with its
    // codec and any nested children) comes from [[renderRecord]],
    // which is also reused when a nested child needs to be rendered
    // inline into its parent's companion body.
    val template =
      s"""|package $ModelsPkg
          |
          |${scaladoc(td.doc)}${header}
          |
          |import com.alexdupre.klaviyo.core.Codecs.given
          |import com.alexdupre.klaviyo.core.Tristate
          |import com.github.plokhotnyuk.jsoniter_scala.core.*
          |import com.github.plokhotnyuk.jsoniter_scala.macros.*
          |
          |${renderRecord(td)}
          |""".stripMargin

    EmittedFile(s"models/${td.name}.scala", validate(template))
  }

  /** Produce the Scala source for `final case class X(...)` plus
    * `object X { ... }`, including codecs (Wire-shim or plain) and
    * any nested children. No package declaration, no imports — the
    * caller wraps that based on context (top-level file vs. nested
    * inline into a parent companion). Indentation is intentionally
    * flat — scalafmt re-indents at the final pass.
    *
    * Used at two sites:
    *   - [[emitRecord]] wraps this in package + imports for a
    *     top-level record (`parent.isEmpty`).
    *   - When a parent record has children, this same function is
    *     invoked recursively for each child and the result is
    *     inlined into the parent's companion body before the
    *     parent's own codec(s).
    *
    * The Scala class name used in declarations and codec types is
    * the record's [[TypeDef.Record.shortName]] when nested, the full
    * name otherwise. Field types resolve via [[renderScalaType]],
    * which dots through the display-name map so a sibling reference
    * `Ref("FooBar")` renders as `Foo.Bar` from anywhere.
    */
  private def renderRecord(td: TypeDef.Record): String = {
    // Local class name: short form for nested records (rendered
    // inside their parent's companion), full form for top-level.
    val localName = td.shortName.getOrElse(td.name)

    /** Render one visible field of the public case class. Used for
      * both the plain-record path and the Wire-shim path (where the
      * same lines reappear on the `Wire` mirror class).
      */
    def fieldLine(f: FieldPlan, isLast: Boolean): String = {
      val sep = if (isLast) "" else ","
      val tpe = renderField(f)
      // Precedence for the field's default value:
      //   1. an explicit `default: …` from the spec (already rendered
      //      to Scala source text by `renderDefault`, pre-wrapped for
      //      the field's Tristate shape);
      //   2. `Tristate.Absent` when the field is optional (valid for
      //      both `Optional[T]` and `Maybe[T]`);
      //   3. no default (required-and-non-nullable, or
      //      required-and-nullable Nullable[T] — caller must supply
      //      a value or `Tristate.Null`).
      val default = f.default match {
        case Some(text) => s" = $text"
        case None => if (f.required) "" else " = com.alexdupre.klaviyo.core.Tristate.Absent"
      }
      val rename =
        if (autoSnakeCase(f.scalaName) == f.jsonName) ""
        else s"""@named("${escapeStr(f.jsonName)}") """
      val doc = fieldScaladoc(f.doc)
      s"$doc  $rename${f.scalaName}: $tpe$default$sep"
    }

    val publicFieldLines = td.fields.zipWithIndex
      .map { case (f, i) => fieldLine(f, i == td.fields.size - 1) }
      .mkString("\n")

    val classBody = if (td.fields.isEmpty)
      s"final case class $localName()"
    else
      s"""|final case class $localName(
          |$publicFieldLines
          |)""".stripMargin

    // Nested children — both records AND single-parent inline
    // enums — are rendered FIRST in the companion body so they (and
    // their codecs) are in scope when the parent's macro-derived
    // codec runs implicit search for inner types. Each child record
    // is rendered recursively — grandchildren end up nested inside
    // their immediate parent's companion, not flattened up.
    //
    // Enums are emitted before records: jsoniter's macro searches
    // for `JsonValueCodec[ChildEnum]` while compiling the parent's
    // codec, and Scala 3's implicit resolution walks the companion
    // top-down, so earlier definitions are in scope for later ones.
    // The order doesn't strictly matter for a given child type
    // (each is its own object with its own givens) but keeping
    // enums first is a tiny readability win — they tend to be
    // shorter, and parent records often reference them.
    val nestedEnums = childEnumsByParent.getOrElse(td.name, Nil)
    val nestedPrimitiveUnions = childPrimitiveUnionsByParent.getOrElse(td.name, Nil)
    val nestedResourceUnions = childResourceUnionsByParent.getOrElse(td.name, Nil)
    val nestedRecords = childRecordsByParent.getOrElse(td.name, Nil)
    val nestedRendered = (
      nestedEnums.map(renderEnum)
        ++ nestedPrimitiveUnions.map(renderPrimitiveUnion)
        ++ nestedResourceUnions.map(renderResourceUnion)
        ++ nestedRecords.map(renderRecord)
    ).mkString("\n\n")

    // Records whose field set includes a `java.nio.file.Path` (from a
    // `string + format: binary` schema) are multipart bodies, not
    // JSON. We can't macro-derive a `JsonValueCodec[Path]`, and the
    // multipart code path in the API method emitter ignores the
    // record's codec anyway. Skip codec emission for such records;
    // the case-class declaration still appears so the type exists in
    // user code and the multipart-body assembly can read fields off
    // it.
    val isMultipartShape = td.fields.exists(f => isBinaryFile(f.scalaType))

    val companionBody = if (isMultipartShape) {
      s"""|$nestedRendered""".stripMargin
    } else if (td.hiddenDiscriminators.isEmpty) {
      // Plain macro-derived codec — no Wire shim needed.
      s"""|$nestedRendered
          |
          |  given JsonValueCodec[$localName] = JsonCodecMaker.make(
          |    CodecMakerConfig
          |      .withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
          |      .withAllowRecursiveTypes(true)
          |  )
          |
          |  given JsonValueCodec[Vector[$localName]] = JsonCodecMaker.make(
          |    CodecMakerConfig.withAllowRecursiveTypes(true)
          |  )""".stripMargin
    } else {
      // Wire-shim path — at least one field is a single-value enum
      // discriminator (the JSON:API `type` field). The shim keeps
      // the discriminator off the public case class while still
      // round-tripping it through jsoniter's macro codec on a
      // private mirror class.

      // Discriminator fields appear first on the wire (matches
      // Klaviyo's `{ "type": …, "id": …, "attributes": … }` ordering)
      // and use a backtick-wrapped, jsonName-derived Scala identifier.
      // `@named` is emitted unconditionally so the wire name is
      // exact regardless of the field-name mapper.
      val discFieldLines = td.hiddenDiscriminators.zipWithIndex.map { case (d, i) =>
        val nameOnWire = escapeStr(d.jsonName)
        val needsComma = i < td.hiddenDiscriminators.size - 1 || td.fields.nonEmpty
        val sep = if (needsComma) "," else ""
        val ident = s"`${d.jsonName}`"
        s"""  @named("$nameOnWire") $ident: String$sep"""
      }.mkString("\n")

      val wireVisibleFieldLines = td.fields.zipWithIndex
        .map { case (f, i) => fieldLine(f, i == td.fields.size - 1) }
        .mkString("\n")

      val wireFieldLines = (discFieldLines, wireVisibleFieldLines) match {
        case (d, "") => d
        case ("", v) => v
        case (d, v) => s"$d\n$v"
      }

      val discriminatorChecks = td.hiddenDiscriminators.map { d =>
        val nameOnWire = escapeStr(d.jsonName)
        val constant = escapeStr(d.constantValue)
        val ident = s"`${d.jsonName}`"
        s"""      if (w.$ident != "$constant") in.decodeError("expected `$nameOnWire`=\\"$constant\\", got: " + w.$ident)"""
      }.mkString("\n")

      // Constructor invocation for the public case class on decode.
      val publicCtor =
        if (td.fields.isEmpty) s"$localName()"
        else s"""$localName(${td.fields.map(f => s"w.${f.scalaName}").mkString(", ")})"""

      // Positional construction of Wire on encode: discriminator
      // constants first, then each public field threaded through `x`.
      val wireCtor = {
        val constants = td.hiddenDiscriminators.map(d => "\"" + escapeStr(d.constantValue) + "\"")
        val fields = td.fields.map(f => s"x.${f.scalaName}")
        s"""Wire(${(constants ++ fields).mkString(", ")})"""
      }

      s"""|$nestedRendered
          |
          |  private final case class Wire(
          |$wireFieldLines
          |  )
          |
          |  private given JsonValueCodec[Wire] = JsonCodecMaker.make(
          |    CodecMakerConfig
          |      .withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
          |      .withAllowRecursiveTypes(true)
          |  )
          |
          |  given JsonValueCodec[$localName] = new JsonValueCodec[$localName] {
          |    def decodeValue(in: JsonReader, default: $localName): $localName = {
          |      val w = summon[JsonValueCodec[Wire]].decodeValue(in, null.asInstanceOf[Wire])
          |$discriminatorChecks
          |      $publicCtor
          |    }
          |    def encodeValue(x: $localName, out: JsonWriter): Unit =
          |      summon[JsonValueCodec[Wire]].encodeValue($wireCtor, out)
          |    def nullValue: $localName = null.asInstanceOf[$localName]
          |  }
          |
          |  given JsonValueCodec[Vector[$localName]] = JsonCodecMaker.make(
          |    CodecMakerConfig.withAllowRecursiveTypes(true)
          |  )""".stripMargin
    }

    // Nested records carry their own scaladoc; top-level records
    // already render the scaladoc above the `package` line via
    // [[emitRecord]], so don't repeat it here when we know we're
    // top-level (parent.isEmpty + we're at the top of a file).
    val docPrefix = if (td.parent.isDefined) scaladoc(td.doc) else ""

    s"""|$docPrefix$classBody
        |
        |object $localName {
        |$companionBody
        |}""".stripMargin
  }

  // ------------------------------------------------------------------
  // API class
  // ------------------------------------------------------------------

  private def emitApi(cat: CategoryPlan): EmittedFile = {
    val basePkg = s"$ApiPkg.${cat.packageName}"
    val apiName = s"${cat.className}Api"
    val methods = cat.operations.map(emitMethod).mkString("\n\n")

    val template =
      s"""|package $basePkg
          |
          |${header}
          |
          |import com.alexdupre.klaviyo.core.Tristate
          |import com.alexdupre.klaviyo.core.KlaviyoClient
          |import com.alexdupre.klaviyo.core.internal.Executor
          |import com.github.plokhotnyuk.jsoniter_scala.core.*
          |import sttp.client4.*
          |
          |import $ModelsPkg.*
          |
          |/** Generated client for Klaviyo's `${cat.className}` category.
          |  *
          |  * Construct via the extension method on [[KlaviyoClient]]:
          |  *
          |  * {{{
          |  * import $RootPkg.*
          |  *
          |  * val client = KlaviyoClient(backend, config)
          |  * client.${cat.packageName}.???
          |  * }}}
          |  */
          |final class $apiName[F[_]](private val client: KlaviyoClient[F]) {
          |
          |$methods
          |}
          |""".stripMargin

    EmittedFile(s"api/${cat.packageName}/$apiName.scala", validate(template))
  }

  /** Emit one method of the API class. */
  private def emitMethod(op: OperationPlan): String = {
    // The `revision` header is injected by the executor from the
    // client config; dropping it from the method signature keeps the
    // common case clean.
    val visible = op.parameters.filterNot(p => p.location == ParamLocation.Header && p.jsonName == "revision")

    val pathParams = visible.filter(_.location == ParamLocation.Path)
    val bodyParam = visible.find(_.location == ParamLocation.Body)
    val others = visible.filterNot(p => p.location == ParamLocation.Path || p.location == ParamLocation.Body)
    val (othersReq, othersOpt) = others.partition(_.required)
    val ordered = pathParams ++ bodyParam.toList ++ othersReq.sortBy(_.scalaName) ++ othersOpt.sortBy(_.scalaName)

    val paramDecls = ordered.map { p =>
      val tpe = if (p.required) renderScalaType(p.scalaType)
      else s"Tristate.Optional[${renderScalaType(p.scalaType)}]"
      val default = if (p.required) "" else " = Tristate.Absent"
      s"      ${p.scalaName}: $tpe$default"
    }.mkString(",\n")

    val pathExpr = substitutePathTemplate(op.pathTemplate)
    val queryParams = visible.filter(_.location == ParamLocation.Query)
    val queryLines = queryParams.map(emitQueryParamLine).mkString("\n")
    val headerParams = visible.filter(_.location == ParamLocation.Header)
    val headerLines = headerParams.map(emitHeaderLine).mkString("\n")

    val bodyAttach = bodyParam match {
      case None => ""
      case Some(p) if op.bodyContentType.contains("multipart/form-data") =>
        // Multipart upload — walk the body record's fields and emit
        // one `multipart`/`multipartFile` call each. Required fields
        // are unconditional; optional fields are gated on `toOption`.
        emitMultipartBody(op, p)
      case Some(p) =>
        // Default: JSON body. JSON:API content type unless the spec
        // declared something more specific that we still serialise
        // as JSON (e.g. `application/json`).
        val typeStr = renderScalaType(p.scalaType)
        val ct = op.bodyContentType.getOrElse("application/vnd.api+json")
        if (p.required) {
          s"""    req = req
             |      .body(writeToString[$typeStr](${p.scalaName}))
             |      .contentType("$ct")""".stripMargin
        } else {
          s"""    ${p.scalaName}.foreach { v =>
             |      req = req
             |        .body(writeToString[$typeStr](v))
             |        .contentType("$ct")
             |    }""".stripMargin
        }
    }

    val returnTpe = renderScalaType(op.successType)
    val doc = scaladoc(op.description.orElse(op.summary))

    val decode =
      if (op.successType == ScalaType.Unit) "    Executor.execute(client, req) { _ => () }"
      else
        s"""|    Executor.execute(client, req) { body =>
            |      readFromString[$returnTpe](body)
            |    }""".stripMargin

    s"""|$doc
        |  def ${op.scalaName}(
        |$paramDecls
        |  ): F[$returnTpe] = {
        |    var uri = uri"$${client.config.baseUri}$pathExpr"
        |$queryLines
        |    var req = basicRequest.${op.httpMethod.toLowerCase}(uri).response(asStringAlways)
        |$headerLines
        |$bodyAttach
        |$decode
        |  }""".stripMargin
  }

  /** True iff the rendered Scala type is `java.nio.file.Path` —
    * planner emits this for `string + format: binary` fields, which
    * only appear in multipart bodies. Used both to skip JSON codec
    * generation on the enclosing record and to pick the right sttp
    * helper when assembling parts.
    */
  private def isBinaryFile(t: ScalaType): Boolean = t match {
    case ScalaType.Ref("java.nio.file.Path") => true
    case _ => false
  }

  /** Emit the request-mutation snippet for a multipart body.
    *
    * Looks up the body record's field set via [[recordsByName]] and
    * produces one `multipart`/`multipartFile` call per field:
    *
    *   - `java.nio.file.Path` field → `multipartFile("name", value)`.
    *     sttp's helper handles streaming the file content and the
    *     correct Content-Disposition header.
    *   - Anything else → `multipart("name", value.toString)`. String
    *     fields skip the `.toString`; everything else (booleans,
    *     numbers, dates, ...) reduces to a string part with their
    *     ordinary string form.
    *
    * Optional (Tristate) fields are gated on `Tristate#foreach` so a
    * missing value contributes no part. Required fields are always
    * appended. The whole body parameter may itself be optional, in
    * which case the entire block runs only when the body's `foreach`
    * yields a value.
    */
  private def emitMultipartBody(op: OperationPlan, p: ParamPlan): String = {
    val recordName = p.scalaType match {
      case ScalaType.Ref(qn) => qn
      case _ => ""
    }
    val rec = recordsByName.getOrElse(
      recordName,
      sys.error(
        s"Multipart body for '${op.scalaName}' references unknown record '$recordName' — " +
          "planner produced a body shape that's not in the type table"
      )
    )

    // Render the string the codec calls `value.toString` on, given
    // an in-scope identifier and a field plan. Strings pass through
    // unchanged so we don't double-quote.
    def stringExprOf(field: FieldPlan, ident: String): String =
      field.scalaType match {
        case ScalaType.Str => ident
        case _ => s"$ident.toString"
      }

    val partLines = rec.fields.map { f =>
      val accessor = s"body.${f.scalaName}"
      val partExpr: String => String = ident =>
        if (isBinaryFile(f.scalaType)) s"""multipartFile("${escapeStr(f.jsonName)}", $ident)"""
        else s"""multipart("${escapeStr(f.jsonName)}", ${stringExprOf(f, ident)})"""
      if (f.required) s"""        parts = parts :+ ${partExpr(accessor)}"""
      else
        s"""        $accessor.foreach { v =>
           |          parts = parts :+ ${partExpr("v")}
           |        }""".stripMargin
    }.mkString("\n")

    // Keep the `= …` initialiser on a single line — once scalafmt
    // re-indents the body, a continuation line on the RHS of a `var`
    // declaration in Scala 3 risks being parsed as an indented block,
    // which then swallows the subsequent `parts = parts :+ …`
    // statements. Putting the initialiser inline avoids the
    // ambiguity entirely.
    val core =
      s"""|      var parts: Seq[sttp.model.Part[BasicBodyPart]] = Vector.empty
          |$partLines
          |      req = req.multipartBody(parts)""".stripMargin

    if (p.required) {
      // Strip one level of leading indent so it lines up with the rest
      // of the method body (the helper string is indented one extra
      // level above for the optional-wrapping case).
      core.linesIterator.map(_.stripPrefix("  ")).mkString("\n")
    } else {
      s"""    ${p.scalaName}.foreach { body =>
         |$core
         |    }""".stripMargin
    }
  }

  private def emitQueryParamLine(p: ParamPlan): String = {
    val ident = p.scalaName
    val key = escapeStr(p.jsonName)
    val isArr = p.scalaType match { case _: ScalaType.App => true; case _ => false }
    val isFilter = p.scalaType == ScalaType.Ref("com.alexdupre.klaviyo.core.Filter")
    def encode(varName: String): String = {
      if (isArr) s"""$varName.mkString(",")"""
      else if (isFilter) s"$varName.render"
      else s"$varName.toString"
    }
    if (p.required) {
      s"""    uri = uri.addParam("$key", ${encode(ident)})"""
    } else {
      s"""    $ident.foreach { v => uri = uri.addParam("$key", ${encode("v")}) }"""
    }
  }

  private def emitHeaderLine(p: ParamPlan): String = {
    val ident = p.scalaName
    val key = escapeStr(p.jsonName)
    if (p.required) s"""    req = req.header("$key", $ident.toString)"""
    else s"""    $ident.foreach { v => req = req.header("$key", v.toString) }"""
  }

  // ------------------------------------------------------------------
  // Per-operation typed filter builder
  // ------------------------------------------------------------------

  /** Emit a typed filter-builder object for an operation that
    * declares `x-klaviyo-filters`. The object lives alongside the
    * Api class in the category's package and contains one nested
    * `object` per filter field, with methods per legal operator.
    *
    * Operator naming rules:
    *   - kebab-case operator names are camelCased
    *     (`greater-or-equal` → `greaterOrEqual`).
    *   - single-value form gets the bare name.
    *   - list-value form gets the bare name UNLESS the same operator
    *     also appears in single form, in which case the list variant
    *     is suffixed `In` (`equals(v)` for single, `equalsIn(vs*)`
    *     for list).
    *   - zero-value form (`none`) gets the bare name.
    */
  private def emitFilter(cat: CategoryPlan, op: OperationPlan): Option[EmittedFile] = op.filter.map { fp =>
    val fieldsBlock = fp.fields.map(emitFilterField).mkString("\n\n")
    // Filter builders live in the flat `models` package alongside
    // DTOs and enums: they're user-side construction code (the type
    // a caller assembles before invoking an operation), so the same
    // `import com.alexdupre.klaviyo.models.*` that pulls in the
    // payload types also brings in their filter builders. Names are
    // already globally unique — Klaviyo's `operationId`s are
    // unique, and the builder name is derived from them.
    val template =
      s"""|package $ModelsPkg
          |
          |${header}
          |
          |import com.alexdupre.klaviyo.core.Filter
          |
          |/** Typed filter builder for `client.${cat.packageName}.${op.scalaName}`.
          |  *
          |  * Each nested object corresponds to one filterable field
          |  * declared by Klaviyo's `x-klaviyo-filters` extension.
          |  * Calling an operator method returns a [[Filter]] value;
          |  * compose multiple filters with `f1 and f2` or `Filter.all(...)`.
          |  *
          |  * {{{
          |  * import $RootPkg.*
          |  * import $RootPkg.models.${fp.objectName}.*
          |  *
          |  * val f = ???   // build a Filter via the nested objects below
          |  * client.${cat.packageName}.${op.scalaName}(filter = f)
          |  * }}}
          |  */
          |object ${fp.objectName} {
          |
          |$fieldsBlock
          |}
          |""".stripMargin
    EmittedFile(s"models/${fp.objectName}.scala", validate(template))
  }

  /** Emit one filter-field nested object. */
  private def emitFilterField(f: FilterFieldPlan): String = {
    // Decide naming: which list ops collide with single ops?
    val singleSet = f.singleOps.toSet
    val singleLines = f.singleOps.map { op =>
      val mName = kebabToCamel(op)
      val (paramT, render) = renderValueForArg(f.valueKind, "value")
      s"""  def $mName(value: $paramT): Filter = Filter(s"$op(${f.jsonName},$$${render})")"""
    }
    val listLines = f.listOps.map { op =>
      val baseName = kebabToCamel(op)
      val mName = if (singleSet.contains(op)) s"${baseName}In" else baseName
      val (paramT, _) = renderValueForArg(f.valueKind, "values")
      // Varargs interpolation: list-form operators produce
      // `op(field,v1,v2,...)`. We render each value via the kind-
      // appropriate helper and join with `,`. Per Klaviyo's filter
      // grammar, only strings are quoted; numbers, booleans, and
      // ISO-8601 datetimes are bare literals.
      val perValueRender = f.valueKind match {
        case FilterValueKind.Str => "Filter.quoteString(v)"
        case FilterValueKind.DateTime => "v.toString"
        case FilterValueKind.Bool => "Filter.renderBoolean(v)"
        case FilterValueKind.Int_ => "Filter.renderNumber(v)"
        case FilterValueKind.Dbl => "Filter.renderNumberD(v)"
      }
      s"""  def $mName(values: $paramT*): Filter = Filter(s"$op(${f.jsonName},$${values.map(v => $perValueRender).mkString(\",\")})")"""
    }
    val noneLines = f.noneOps.map { op =>
      val mName = kebabToCamel(op)
      s"""  def $mName(): Filter = Filter(s"$op(${f.jsonName})")"""
    }

    val doc = if (f.enumValues.isEmpty) ""
    else s"  /** Allowed values: ${f.enumValues.map(v => s"`$v`").mkString(", ")}. */\n"

    val body = (singleLines ++ listLines ++ noneLines).mkString("\n")
    s"""|${doc}object ${f.scalaName} {
        |$body
        |}""".stripMargin
  }

  /** Return `(Scala parameter type, value-rendering expression)` for a
    * single-value filter operator. The rendering expression assumes
    * the argument variable name (`"value"` or `"v"` in varargs
    * contexts) is in scope at the call site.
    *
    * Per Klaviyo's filter grammar (per the public filtering docs):
    *
    *   - Strings are double-quoted (`"value"`), with embedded
    *     double quotes backslash-escaped. The double quote itself
    *     URI-encodes to `%22` when sttp serialises the query
    *     parameter.
    *   - Numbers (integer and floating) are bare literals.
    *   - Booleans are bare `true` / `false`.
    *   - Datetimes are bare unquoted ISO-8601 RFC-3339 strings
    *     (`OffsetDateTime.toString`).
    */
  private def renderValueForArg(kind: FilterValueKind, argName: String): (String, String) = kind match {
    case FilterValueKind.Str => ("String", s"{Filter.quoteString($argName)}")
    case FilterValueKind.DateTime => ("java.time.OffsetDateTime", s"{$argName.toString}")
    case FilterValueKind.Bool => ("Boolean", s"{Filter.renderBoolean($argName)}")
    case FilterValueKind.Int_ => ("Long", s"{Filter.renderNumber($argName)}")
    case FilterValueKind.Dbl => ("Double", s"{Filter.renderNumberD($argName)}")
  }

  /** kebab-case → camelCase: `greater-or-equal` → `greaterOrEqual`. */
  private def kebabToCamel(s: String): String = {
    val parts = s.split("-").filter(_.nonEmpty)
    if (parts.isEmpty) s
    else parts.head + parts.tail.map(capitalize).mkString
  }

  // ------------------------------------------------------------------
  // Extensions object — one per category
  // ------------------------------------------------------------------

  private def emitExtensions(cat: CategoryPlan): EmittedFile = {
    val basePkg = s"$ApiPkg.${cat.packageName}"
    val apiName = s"${cat.className}Api"

    val template =
      s"""|package $basePkg
          |
          |${header}
          |
          |import com.alexdupre.klaviyo.core.KlaviyoClient
          |
          |/** Extension wiring `.${cat.packageName}` onto `KlaviyoClient[F]`.
          |  *
          |  * The extension lives inside an `object Extensions` so that the
          |  * top-level `$RootPkg` package object can
          |  * `export Extensions.*` to compose every category into a
          |  * single import.
          |  */
          |object Extensions {
          |  // Single-line extension form is the most portable across
          |  // Scala 3 minor releases — the brace form
          |  // `extension (x) { def y = … }` and the indented form both
          |  // had parsing regressions on 3.8.x for single-method
          |  // extension blocks nested inside an `object`, producing
          |  // confusing "Extension without extension methods" errors.
          |  extension [F[_]](c: KlaviyoClient[F]) def ${cat.packageName}: $apiName[F] = new $apiName[F](c)
          |}
          |
          |export Extensions.*
          |""".stripMargin

    EmittedFile(s"api/${cat.packageName}/Extensions.scala", validate(template))
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  private def validate(source: String): String = {
    Scala3(source).parse[Source] match {
      case Parsed.Success(tree) => tree.syntax
      case Parsed.Error(pos, msg, _) =>
        throw new RuntimeException(
          s"Generated code failed to parse: $msg\n" +
            s"  at line ${pos.startLine + 1}, col ${pos.startColumn + 1}\n" +
            s"  source:\n${withLineNumbers(source)}"
        )
    }
  }

  private def withLineNumbers(source: String): String =
    source.linesIterator.zipWithIndex
      .map { case (l, i) => f"${i + 1}%4d: $l" }
      .mkString("\n")

  /** Render a [[ScalaType]] for use in emitted source. Plain bare
    * names ([[ScalaType.Ref]] whose `qualifiedName` carries no dot —
    * our local naming convention) are resolved through
    * [[displayNameMap]] so a nested record's reference site renders
    * as `Parent.Child`. Fully qualified refs (e.g. core types like
    * `com.alexdupre.klaviyo.core.jsonapi.ResourceLinks`) pass through
    * untouched.
    */
  private def renderScalaType(t: ScalaType): String = t match {
    case ScalaType.Ref(qn) if !qn.contains('.') => displayName(qn)
    case ScalaType.App(c, args) => s"$c[${args.map(renderScalaType).mkString(", ")}]"
    case other => other.render
  }

  /** Render a record field's Scala type via the four-cell
    * `(required, nullable)` mapping to the phantom-state aliases of
    * `Tristate`.
    */
  private def renderField(f: FieldPlan): String = {
    val inner = renderScalaType(f.scalaType)
    (f.required, f.nullable) match {
      case (true, false) => inner
      case (true, true) => s"Tristate.Nullable[$inner]"
      case (false, false) => s"Tristate.Optional[$inner]"
      case (false, true) => s"Tristate.Maybe[$inner]"
    }
  }

  /** Scaladoc block. Empty / blank input returns the empty string so
    * the caller can prepend without producing a stray comment.
    */
  private def scaladoc(doc: Option[String]): String = doc.filter(_.trim.nonEmpty) match {
    case None => ""
    case Some(d) =>
      val lines = d.linesIterator.toList.map(_.stripLineEnd.replace("*/", "*\\/"))
      if (lines.lengthCompare(1) <= 0) s"/** ${lines.head.trim} */\n"
      else {
        val body = lines.tail.map(l => s"  * $l").mkString("\n")
        s"/** ${lines.head}\n$body\n  */\n"
      }
  }

  /** Like [[scaladoc]] but indented for a case-class field position.
    * Returns the docblock followed by a newline (so the caller can
    * prepend without worrying about layout), or `""` when there is
    * no description. Descriptions containing a literal end-of-doc
    * sequence are escaped (same approach as [[scaladoc]]) to keep
    * the emitted comment block well-formed.
    */
  private def fieldScaladoc(doc: Option[String]): String = doc.filter(_.trim.nonEmpty) match {
    case None => ""
    case Some(d) =>
      val lines = d.linesIterator.toList.map(_.stripLineEnd.replace("*/", "*\\/"))
      if (lines.lengthCompare(1) <= 0) s"  /** ${lines.head.trim} */\n"
      else {
        val body = lines.tail.map(l => s"    * $l").mkString("\n")
        s"  /** ${lines.head}\n$body\n    */\n"
      }
  }

  private def header: String = "// GENERATED by klaviyo4s codegen — do not edit by hand."

  /** Render a string-enum wire value as a Scala case-name identifier,
    * preserving sign and path characters when they carry meaning the
    * caller can't otherwise see at the call site:
    *
    *   - `/` is always preserved (timezone identifiers, path-shaped
    *     enums).
    *   - `-` and `+` are preserved only when they are the FIRST
    *     character (descending sort markers like `-created_at`) or
    *     when the next character is a digit (numeric operators like
    *     `Etc/GMT-0`, `Etc/GMT+12`). When a sign sits between two
    *     letters it's a plain word separator (`web-feed`, `tag-group`)
    *     and gets dropped — those values collapse to clean PascalCase
    *     (`WebFeed`, `TagGroup`) without backticks.
    *
    * If after applying these rules the result contains any of
    * `+`/`-`/`/`, the name is wrapped in backticks (Scala 3 allows
    * arbitrary characters inside backtick identifiers). Otherwise the
    * plain PascalCase name is emitted.
    */
  private def enumCaseName(value: String): String = {
    def keepSign(idx: Int): Boolean = {
      val c = value.charAt(idx)
      if (c != '-' && c != '+') false
      else if (idx == 0) true
      else if (idx + 1 < value.length && value.charAt(idx + 1).isDigit) true
      else false
    }
    // "Shouty snake" inputs (no lowercase letters at all — `HARD_BOUNCE`,
    // `AUTHORIZED`, `AD`, ...) get word-by-word PascalCasing: the
    // word-initial char is uppercased, the rest of the word is
    // lowercased. So `HARD_BOUNCE` becomes `HardBounce`, not the
    // previously-emitted `HARDBOUNCE`.
    //
    // Mixed-case inputs (any value that contains at least one
    // lowercase letter) are left verbatim — Klaviyo's timezone IDs
    // and camelCased values encode meaningful internal capitals
    // (`GMT`, `AF`, `fitToText`, `con_AF`) that we must not flatten.
    // The word-initial char is still uppercased to satisfy the
    // Scala identifier convention, but mid-word characters pass
    // through unchanged.
    val shouty = !value.exists(_.isLower)
    val sb = new StringBuilder
    var capitalize = true
    var hasSpecial = false
    var i = 0
    while (i < value.length) {
      val c = value.charAt(i)
      if (c == '/' || keepSign(i)) {
        sb.append(c)
        hasSpecial = true
        capitalize = true
      } else if (c.isLetterOrDigit) {
        if (capitalize) sb.append(c.toUpper)
        else if (shouty) sb.append(c.toLower)
        else sb.append(c)
        capitalize = false
      } else {
        // Plain word separator (`_`, whitespace, between-letter
        // sign char) — discard, force next alphanumeric to capitalise.
        capitalize = true
      }
      i += 1
    }
    val body =
      if (sb.isEmpty) "_"
      else if (sb.charAt(0).isDigit) s"_${sb.toString}"
      else sb.toString
    if (hasSpecial) s"`$body`" else body
  }

  private def capitalize(s: String): String =
    if (s.isEmpty) s else s.head.toUpper +: s.tail

  /** Mirror of jsoniter's `enforce_snake_case` heuristic so we can
    * avoid emitting redundant `@named` annotations on fields whose
    * Scala name already round-trips through the global codec config.
    */
  private def autoSnakeCase(scalaName: String): String = {
    val clean = scalaName.stripPrefix("`").stripSuffix("`")
    val sb = new StringBuilder
    clean.foreach { c =>
      if (c.isUpper) {
        if (sb.nonEmpty) sb.append('_')
        sb.append(c.toLower)
      } else sb.append(c)
    }
    sb.toString
  }

  private def escapeStr(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

  /** Replace `{name}` placeholders with `$camelName` interpolation
    * tokens for use inside an sttp `uri"..."` interpolator. Klaviyo
    * writes path placeholders in snake_case while we expose camelCase
    * parameters; this keeps them aligned.
    */
  private def substitutePathTemplate(template: String): String = {
    val sb = new StringBuilder
    var i = 0
    while (i < template.length) {
      val c = template.charAt(i)
      if (c == '{') {
        val end = template.indexOf('}', i)
        if (end < 0) { sb.append(c); i += 1 }
        else {
          val name = template.substring(i + 1, end)
          val identName = toCamelCase(name)
          sb.append('$').append(identName)
          i = end + 1
        }
      } else {
        sb.append(c)
        i += 1
      }
    }
    sb.toString
  }

  private def toCamelCase(s: String): String = {
    val parts = s.split("[_\\-]").filter(_.nonEmpty)
    if (parts.isEmpty) s
    else parts.head + parts.tail.map(capitalize).mkString
  }
}
