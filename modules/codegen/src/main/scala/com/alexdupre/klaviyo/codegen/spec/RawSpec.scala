package com.alexdupre.klaviyo.codegen.spec

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

import scala.collection.immutable.ListMap

/** Typed model of a Klaviyo OpenAPI 3.0.2 category spec file.
  *
  * "Raw" here means *as-parsed*: this is the structure we get out of the
  * JSON before any normalisation, `\$ref` resolution or `allOf`
  * flattening. The [[com.alexdupre.klaviyo.codegen.model]] layer
  * transforms `RawSpec` into a normalised plan that the emitter
  * consumes.
  *
  * The model captures only what Klaviyo's specs actually use — we do
  * not aim for full OpenAPI 3.0 conformance. Unknown JSON fields are
  * silently dropped by jsoniter (default behaviour). Fields that vary
  * in shape (`example`, `meta`, free-form `default` values) are
  * deliberately omitted; if codegen later needs them we extend the
  * model on demand.
  *
  * All ordered field collections use `scala.collection.immutable.ListMap`
  * so case-class fields and operation lists appear in the same order
  * as the spec — matching Klaviyo's own reference documentation.
  */
final case class RawSpec(
  openapi: String,
  info: RawInfo,
  servers: List[RawServer] = Nil,
  paths: ListMap[String, RawPathItem] = ListMap.empty,
  components: RawComponents = RawComponents(),
  tags: List[RawTag] = Nil
)

object RawSpec {

  implicit val rawSpecCodec: JsonValueCodec[RawSpec] = JsonCodecMaker.make(
    // Allow recursive types — RawSchema is mutually recursive with itself
    // through `properties`, `items`, `allOf` etc.
    //
    // Bump the per-map insert cap: jsoniter defaults to 1024, but
    // Klaviyo's `components.schemas` map currently holds ~1200 entries
    // and growing.
    CodecMakerConfig
      .withAllowRecursiveTypes(true)
      .withMapMaxInsertNumber(16384)
      .withSetMaxInsertNumber(16384)
  )
}

/** OpenAPI `info` block. Carries the spec revision (`version`) which is
  * what `klaviyo4sUpdateRevision` reads to bump `KlaviyoConfig.DefaultRevision`.
  */
final case class RawInfo(
  title: String,
  version: String,
  description: Option[String] = None
)

final case class RawServer(
  url: String,
  description: Option[String] = None
)

final case class RawTag(
  name: String,
  description: Option[String] = None
)

/** OpenAPI `components` block. Klaviyo only uses `schemas` and
  * `securitySchemes` — other component kinds (parameters, responses,
  * requestBodies, headers) are inlined per-operation.
  */
final case class RawComponents(
  schemas: ListMap[String, RawSchema] = ListMap.empty,
  securitySchemes: ListMap[String, RawSecurityScheme] = ListMap.empty
)

final case class RawSecurityScheme(
  `type`: String,
  name: Option[String] = None,
  in: Option[String] = None,
  scheme: Option[String] = None,
  description: Option[String] = None
)

/** A single URL template carrying one or more HTTP method operations.
  *
  * Klaviyo uses GET/POST/PUT/PATCH/DELETE; the rare OPTIONS/HEAD/TRACE
  * methods are not modelled.
  */
final case class RawPathItem(
  get: Option[RawOperation] = None,
  post: Option[RawOperation] = None,
  put: Option[RawOperation] = None,
  patch: Option[RawOperation] = None,
  delete: Option[RawOperation] = None,
  parameters: List[RawParameter] = Nil
) {

  /** Yield each `(method, operation)` pair in canonical HTTP-verb order.
    * Used by the plan layer when iterating operations under a path.
    */
  def methods: List[(String, RawOperation)] = List(
    "GET" -> get,
    "POST" -> post,
    "PUT" -> put,
    "PATCH" -> patch,
    "DELETE" -> delete
  ).collect { case (m, Some(op)) => m -> op }
}

/** A single API operation under a path-method pair.
  *
  * The `x-klaviyo-*` fields preserve Klaviyo's custom extensions.
  *
  * `xKlaviyoFilters` is a map keyed by Klaviyo wire field name (which
  * may contain dots — e.g. `messages.channel` — for nested-path
  * filtering). Each entry describes the legal operators and the
  * value schema. The Phase 5 emitter consumes this to build a typed
  * per-endpoint filter builder.
  */
final case class RawOperation(
  operationId: String,
  summary: Option[String] = None,
  description: Option[String] = None,
  parameters: List[RawParameter] = Nil,
  requestBody: Option[RawRequestBody] = None,
  responses: ListMap[String, RawResponse] = ListMap.empty,
  tags: List[String] = Nil,
  @named("x-klaviyo-ratelimit") xKlaviyoRatelimit: Option[RawXKlaviyoRatelimit] = None,
  @named("x-klaviyo-scopes") xKlaviyoScopes: List[String] = Nil,
  @named("x-klaviyo-operation-aliases") xKlaviyoOperationAliases: List[String] = Nil,
  @named("x-klaviyo-filters") xKlaviyoFilters: ListMap[String, RawFilterField] = ListMap.empty,
  @named("x-klaviyo-pre-release") xKlaviyoPreRelease: Option[String] = None
)

/** A single field's filter declaration in `x-klaviyo-filters`. */
final case class RawFilterField(
  operators: RawFilterOperators = RawFilterOperators(),
  /** Schema describing the *value* a filter operator takes (string,
    * boolean, datetime, enum...). Inline schema, not a `$ref`.
    */
  value: Option[RawSchema] = None
)

/** The operator groupings Klaviyo emits.
  *
  *   - `single`: operators that take exactly one value
  *     (`equals('Sent')`, `contains('foo')`).
  *   - `list`: operators that take a comma-separated list of values
  *     (`any('Sent','Draft')`, `equals('Sent','Draft')`).
  *   - `none`: operators that take no value (rare — e.g. `null`).
  *
  * Each list holds the Klaviyo operator names verbatim (kebab-case).
  * The planner camelCases them when generating Scala method names.
  */
final case class RawFilterOperators(
  single: List[String] = Nil,
  list: List[String] = Nil,
  @named("none") none: List[String] = Nil
)

/** Klaviyo rate-limit metadata: burst and steady-state per-endpoint
  * limits in the form `"350/s"`, `"3500/m"`. The codegen embeds this
  * verbatim into the operation's scaladoc.
  */
final case class RawXKlaviyoRatelimit(
  burst: Option[String] = None,
  steady: Option[String] = None
)

/** A parameter (path / query / header / cookie). Klaviyo also uses
  * bracketed names like `fields[account]` — these come through verbatim
  * in `name`; the plan layer translates them to Scala-legal method
  * argument names while preserving the original for serialisation.
  *
  * `\$ref` is supported because operations occasionally reference
  * shared parameter definitions under `components.parameters` (rare in
  * Klaviyo specs but allowed by OpenAPI).
  */
final case class RawParameter(
  name: Option[String] = None,
  in: Option[String] = None,
  description: Option[String] = None,
  required: Option[Boolean] = None,
  schema: Option[RawSchema] = None,
  style: Option[String] = None,
  explode: Option[Boolean] = None,
  @named("$ref") ref: Option[String] = None
)

final case class RawRequestBody(
  description: Option[String] = None,
  required: Option[Boolean] = None,
  content: ListMap[String, RawMediaType] = ListMap.empty
)

final case class RawResponse(
  description: Option[String] = None,
  content: ListMap[String, RawMediaType] = ListMap.empty,
  headers: ListMap[String, RawHeader] = ListMap.empty,
  @named("$ref") ref: Option[String] = None
)

final case class RawMediaType(
  schema: Option[RawSchema] = None
)

final case class RawHeader(
  description: Option[String] = None,
  schema: Option[RawSchema] = None
)

/** A schema definition. Self-recursive through `properties`, `items`,
  * `allOf` / `oneOf` / `anyOf`, etc.
  *
  * Notable omissions vs. full OpenAPI 3.0:
  *   - `example` / `examples` (purely doc-time; codegen doesn't need)
  *   - `format`, `pattern`, `minimum`, `maximum`, `minLength`, `maxLength`
  *     — none of these influence what code we emit today
  *   - `additionalProperties` (it can be `bool|schema` in OpenAPI; we
  *     decide later whether to handle this irregular shape)
  *
  * The OpenAPI 3.0 `nullable + \$ref` quirk is captured as-is — the plan
  * layer translates `{\$ref:..., nullable:true}` to `Option[T]`.
  */
final case class RawSchema(
  @named("$ref") ref: Option[String] = None,
  `type`: Option[String] = None,
  /** OpenAPI `format` hint, used today to discriminate
    * `integer` between 32-bit (`format: int32` or unspecified) and
    * 64-bit (`format: int64`), and `number` between `float` /
    * `double`. Other values are accepted but unused.
    */
  format: Option[String] = None,
  @named("enum") enumValues: EnumValues = EnumValues.Empty,
  properties: ListMap[String, RawSchema] = ListMap.empty,
  required: List[String] = Nil,
  items: Option[RawSchema] = None,
  allOf: List[RawSchema] = Nil,
  oneOf: List[RawSchema] = Nil,
  anyOf: List[RawSchema] = Nil,
  nullable: Option[Boolean] = None,
  description: Option[String] = None,
  /** OpenAPI `default`. Decoded into a lightly typed
    * [[RawDefault]] so the planner can emit a Scala literal in the
    * generated case-class definition. Free-form object/array
    * defaults aren't supported — they round-trip as
    * [[RawDefault.Skipped]] and the planner ignores them.
    */
  default: Option[RawDefault] = None,
  /** OpenAPI `readOnly`. When true, the property is server-set —
    * clients must not send it. The planner forces such fields to
    * `required = false` so write-side users can omit them; the
    * field stays present in the type and decodes naturally as
    * `Tristate.Value(...)` on response shapes.
    */
  readOnly: Option[Boolean] = None
)

/** A captured `default` value, kept lightly typed because OpenAPI
  * defaults are JSON-Schema-shaped (any JSON token). The planner
  * matches on the variant and renders a Scala literal of the
  * matching kind; unknown variants are [[RawDefault.Skipped]] and
  * don't reach the emitter.
  */
sealed trait RawDefault

object RawDefault {
  final case class Str(value: String) extends RawDefault
  final case class Bool(value: Boolean) extends RawDefault
  final case class I(value: Long) extends RawDefault
  final case class D(value: Double) extends RawDefault
  case object Null extends RawDefault

  /** Any default we couldn't capture into a primitive literal
    * (objects, arrays, or unrecognisable token shapes). The emitter
    * treats this exactly like a missing default.
    */
  case object Skipped extends RawDefault

  implicit val rawDefaultCodec: JsonValueCodec[RawDefault] = new JsonValueCodec[RawDefault] {
    def decodeValue(in: JsonReader, default: RawDefault): RawDefault = {
      val t = in.nextToken()
      in.rollbackToken()
      t match {
        case '"' => Str(in.readString(null))
        case 't' | 'f' => Bool(in.readBoolean())
        case 'n' => in.readNullOrError(Null, "expected null"); Null
        case '-' | '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' =>
          // Distinguish integral from fractional by inspecting the
          // numeric text. We over-approximate fractional → Double.
          val raw = in.readBigDecimal(null).bigDecimal.toPlainString
          if (raw.contains('.') || raw.contains('e') || raw.contains('E')) D(raw.toDouble)
          else I(raw.toLong)
        case _ =>
          in.skip(); Skipped
      }
    }
    def encodeValue(x: RawDefault, out: JsonWriter): Unit = x match {
      case Str(v) => out.writeVal(v)
      case Bool(v) => out.writeVal(v)
      case I(v) => out.writeVal(v)
      case D(v) => out.writeVal(v)
      case Null => out.writeNull()
      case Skipped => out.writeNull()
    }
    def nullValue: RawDefault = Null
  }
}

/** Lightly typed wrapper around the JSON-Schema `enum` array.
  *
  * Klaviyo's specs are not consistent about value types here: most
  * enums are arrays of strings (`["account"]`), but several schemas
  * use Boolean (`[true]`) or integer (`[10, 11, ...]`) constants to
  * pin a property to a single discriminator value. We capture all of
  * them as strings (stringifying primitives during decode) so the
  * planner can decide what to do per shape:
  *
  *   - homogeneous string enum  → emit a Scala 3 `enum` type
  *   - homogeneous Boolean/int  → emit a plain `Boolean`/`Int` field
  *     (Phase 5 may refine to a literal-typed singleton)
  *   - mixed                    → emit `String` and drop the constraint
  *
  * `asStrings` always returns the stringified values; `kind` exposes
  * which primitive JSON type the values came from so the planner can
  * make the above decision.
  */
final case class EnumValues(asStrings: List[String], kind: EnumValues.Kind) {
  def isEmpty: Boolean = asStrings.isEmpty
  def nonEmpty: Boolean = asStrings.nonEmpty
}

object EnumValues {

  /** Primitive JSON kind every entry of the enum came from. */
  sealed trait Kind

  object Kind {
    case object Strings extends Kind
    case object Booleans extends Kind
    case object Numbers extends Kind
    case object Mixed extends Kind
    case object Unknown extends Kind
  }

  val Empty: EnumValues = EnumValues(Nil, Kind.Unknown)

  /** Custom codec that handles any JSON primitive token by stringifying
    * it. Tracks the observed kind so the planner can be selective.
    */
  implicit val enumValuesCodec: JsonValueCodec[EnumValues] = new JsonValueCodec[EnumValues] {
    def decodeValue(in: JsonReader, default: EnumValues): EnumValues = {
      if (!in.isNextToken('[')) in.arrayStartOrNullError()
      val buf = scala.collection.mutable.ListBuffer.empty[String]
      val seen = scala.collection.mutable.Set.empty[Kind]
      if (!in.isNextToken(']')) {
        in.rollbackToken()
        var continue = true
        while (continue) {
          // Peek at the next token to dispatch on JSON type.
          val t = in.nextToken()
          in.rollbackToken()
          val (s, k): (String, Kind) = t match {
            case '"' =>
              in.readString(null) -> Kind.Strings
            case 't' | 'f' =>
              in.readBoolean().toString -> Kind.Booleans
            case 'n' =>
              in.skip(); "null" -> Kind.Unknown
            case '-' | '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' =>
              // jsoniter doesn't expose a "read any number as string"
              // helper, but BigDecimal is exact for everything Klaviyo
              // actually emits in enums (small ints and percentages).
              // `.bigDecimal.toPlainString` avoids scientific notation
              // and accesses the underlying java BigDecimal — Scala's
              // wrapper doesn't expose `toPlainString` directly.
              in.readBigDecimal(null).bigDecimal.toPlainString -> Kind.Numbers
            case _ =>
              in.skip(); "?" -> Kind.Unknown
          }
          buf += s
          seen += k
          continue = in.isNextToken(',')
        }
        if (!in.isCurrentToken(']')) in.arrayEndOrCommaError()
      }
      val resolved = seen.toList match {
        case Nil => Kind.Unknown
        case k :: Nil => k
        case _ => Kind.Mixed
      }
      EnumValues(buf.toList, resolved)
    }
    def encodeValue(x: EnumValues, out: JsonWriter): Unit = {
      out.writeArrayStart()
      x.asStrings.foreach(out.writeVal)
      out.writeArrayEnd()
    }
    def nullValue: EnumValues = Empty
  }
}
