package com.alexdupre.klaviyo.codegen.model

/** Normalised plan for the whole Klaviyo API surface, ready for the
  * emitter to walk.
  *
  * The raw OpenAPI spec is irregular: schemas reference each other via
  * `\$ref`, anonymous inline object schemas have no name, `allOf` is
  * used as a composition mechanism rather than the inheritance OpenAPI
  * pretends it represents. The plan layer's job is to flatten all of
  * that into a straightforward, ordered list of Scala type definitions
  * plus a list of categories (each carrying just its operations). The
  * emitter is then a pure pretty-printer over this plan.
  *
  * Types are flat — every DTO lives in one place
  * (`com.alexdupre.klaviyo.models`). Categories carry only operations;
  * the type-vs-category cleavage tracks how Klaviyo organises their
  * documentation while preserving full cross-category interop on the
  * Scala side.
  *
  * @param revision   `info.version` from the spec — the active API
  *                   revision date string (e.g. `"2026-04-15"`)
  * @param types      every named schema in the spec, after `allOf`
  *                   flattening and synthetic-name extraction. The
  *                   order matters: synthetic types follow their
  *                   parent so a top-down scan reads naturally.
  * @param categories one entry per operation tag in the spec. Each
  *                   category names its package / class and carries
  *                   the operations it owns.
  */
final case class SpecPlan(
  revision: String,
  types: List[TypeDef],
  categories: List[CategoryPlan]
)

/** A single API category — corresponds to one Klaviyo operation tag
  * (`"Events"`, `"Custom Objects"`, ...).
  *
  * Holds only operations; types live globally on the parent
  * [[SpecPlan]] so the same `ProfileEnum` (or any other DTO) is one
  * Scala type regardless of which category's API method references
  * it.
  */
final case class CategoryPlan(
  /** Package name and on-disk folder name, derived from the spec
    * tag. Separators (including spaces) are stripped so
    * `"Custom Objects"` becomes `customobjects` — keeps the Scala
    * package and the user's extension call site
    * (`client.customobjects`) free of separators.
    */
  packageName: String,
  /** PascalCased base name for the API class. The emitter appends
    * `Api` to it: `CustomObjects` → `CustomObjectsApi`. Distinct
    * from [[packageName]] because Scala class names follow
    * PascalCase while package names follow lowercase-no-separator
    * convention.
    */
  className: String,
  operations: List[OperationPlan]
)

/** A type the emitter should produce somewhere under
  * `com.alexdupre.klaviyo.<category>.models`.
  */
sealed trait TypeDef {

  /** Unqualified Scala name. Globally unique within the category. */
  def name: String

  /** Optional scaladoc body (`description` from the spec, if any). */
  def doc: Option[String]
}

object TypeDef {

  /** Plain product type — emitted as a `final case class` with a
    * companion that derives the jsoniter codec.
    */
  final case class Record(
    name: String,
    fields: List[FieldPlan],
    doc: Option[String],
    /** Synthetic constant-valued fields the case class hides from
      * its public constructor. The emitter writes a "Wire shim"
      * codec that injects each constant on encode and validates it
      * on decode; the user-facing record never carries them.
      *
      * Used for JSON:API resource-type discriminators: every
      * `type: AccountEnum.Account`-style field whose schema is a
      * single-value string enum becomes one entry here, removing
      * the boilerplate from both the case class signature and
      * every call site. Empty for records without single-value
      * discriminator fields.
      */
    hiddenDiscriminators: List[HiddenDiscriminator] = Nil,
    /** Full Scala name of the parent record (e.g.
      * `"AccountResponseObjectResource"`) when this record was
      * synthesised from an inline-object schema owned by a single
      * parent. `None` for top-level component schemas and for
      * multi-owner shared records (merged-oneOf cache hits).
      *
      * Records with a parent are emitted as nested case classes
      * inside the parent's companion object rather than as
      * standalone files under `models/`. Call sites address them
      * through the dotted name (`Parent.Child`). The reachability
      * sweep still treats them as ordinary plan members — the
      * nesting is purely an emit-time concern.
      */
    parent: Option[String] = None,
    /** Short Scala name for this record inside its parent's
      * companion (e.g. `"Attributes"`, `"Links"`, `"Data"`). Set
      * only when [[parent]] is set; the emitter uses it as the
      * nested case-class name. The full name remains unique in
      * the plan, so the codegen pipeline can still address every
      * record by its un-nested name during planning.
      */
    shortName: Option[String] = None
  ) extends TypeDef

  /** Closed set of string-valued constants. Emitted as a Scala 3 `enum`
    * with explicit `case` literals plus a jsoniter codec that maps to
    * and from the underlying strings.
    *
    * Klaviyo uses these heavily — every JSON:API resource has a `type`
    * discriminator of this shape (`AccountEnum = enum { account }`).
    *
    * `parent` / `shortName` mirror the same fields on [[Record]]:
    * when set, the emitter nests the enum into the parent record's
    * companion object rather than emitting it as a standalone file
    * under `models/`. The planner only sets them for inline-
    * synthesised enums that have exactly one parent — shared enums
    * stay top-level. Top-level component-schema enums (from
    * `components.schemas`) also stay top-level.
    */
  final case class StringEnum(
    name: String,
    values: List[String],
    doc: Option[String],
    parent: Option[String] = None,
    shortName: Option[String] = None
  ) extends TypeDef

  /** Sealed union over a small set of JSON primitive kinds — the
    * typed representation of an OpenAPI `oneOf` whose items are all
    * primitives, e.g. `oneOf: [{type: string}, {type: number}]`.
    *
    * Each variant becomes a `final case class` wrapping the single
    * underlying value; the emitter writes a custom codec that
    * dispatches on the next JSON token (`"` for strings, digits for
    * numbers, `t`/`f` for booleans, `n` for null).
    *
    * Klaviyo uses this primarily for `ProfileLocation.latitude` /
    * `.longitude`, which can come back either as the canonical
    * number or as a stringified number depending on import vintage.
    */
  final case class PrimitiveUnion(
    name: String,
    variants: List[PrimitiveVariant],
    doc: Option[String],
    parent: Option[String] = None,
    shortName: Option[String] = None
  ) extends TypeDef

  /** Sealed union over generated resource case classes, discriminated
    * by the JSON:API `type` field. Used for `included: oneOf(...)`
    * arrays on compound documents — each element can be any of the
    * resource types listed in the `oneOf`, distinguished by `type`.
    *
    * Each variant wraps one inner type; the emitter writes a codec
    * that peeks the `type` field via `setMark`/`rollbackToMark`,
    * then delegates the full decode to the matching inner codec.
    *
    * Klaviyo's spec is structurally guaranteed to provide the `type`
    * field on every JSON:API resource — that is the discriminator we
    * key on.
    */
  final case class ResourceUnion(
    name: String,
    variants: List[ResourceVariant],
    doc: Option[String],
    parent: Option[String] = None,
    shortName: Option[String] = None
  ) extends TypeDef
}

/** One arm of a [[TypeDef.ResourceUnion]]. */
final case class ResourceVariant(
  /** PascalCased Scala case name (`ListResource`, `SegmentResource`, ...). */
  caseName: String,
  /** Wire value of the JSON:API `type` field (`"list"`, `"segment"`, ...). */
  discriminator: String,
  /** Inner Scala type — typically a [[ScalaType.Ref]] to a generated
    * case class.
    */
  scalaType: ScalaType
)

/** One arm of a [[TypeDef.PrimitiveUnion]]. */
final case class PrimitiveVariant(
  /** PascalCased Scala case name (`StringValue`, `NumberValue`, ...). */
  caseName: String,
  /** Which JSON kind this variant maps to. Drives codec dispatch. */
  kind: PrimitiveKind,
  /** Underlying Scala value type (`String`, `Double`, ...). */
  scalaType: ScalaType
)

/** JSON primitive kinds the dispatch codec recognises. The naming
  * matches what JsonReader's token-peek returns logically.
  */
sealed trait PrimitiveKind

object PrimitiveKind {
  case object Str extends PrimitiveKind
  case object Num extends PrimitiveKind
  case object Bool extends PrimitiveKind
  case object Null extends PrimitiveKind
}

/** A constant-valued field the emitter injects via a Wire-shim
  * codec rather than exposing on the case class constructor.
  * See [[TypeDef.Record.hiddenDiscriminators]] for the use case.
  *
  * @param jsonName     wire name of the field (e.g. `"type"`)
  * @param constantValue the literal string the codec writes on
  *                     encode and validates on decode
  */
final case class HiddenDiscriminator(jsonName: String, constantValue: String)

/** A single field on a record. */
final case class FieldPlan(
  /** Scala identifier — guaranteed legal, backticked only if it would
    * otherwise be a reserved word.
    */
  scalaName: String,
  /** Original JSON key as it appears on the wire — preserved so the
    * emitted codec uses `@named("…")` when this differs from
    * `scalaName`.
    */
  jsonName: String,
  scalaType: ScalaType,
  /** Driven by the spec's `required` array. */
  required: Boolean,
  /** Driven by the spec's `nullable` flag. Together with `required`,
    * the emitter picks one of four representations:
    *
    *   - `required, !nullable` → bare `T`
    *   - `required,  nullable` → `Tristate.Nullable[T]`
    *   - `!required, !nullable` → `Tristate.Optional[T]`
    *   - `!required,  nullable` → `Tristate.Maybe[T]`
    */
  nullable: Boolean,
  doc: Option[String],
  /** Scala source-text for the field's default value, when the spec
    * declared `default: …` and the planner could render it to a
    * literal of the right shape. Already wrapped for the field's
    * Tristate representation if applicable (e.g. for an optional
    * boolean with `default: true` this carries
    * `com.alexdupre.klaviyo.core.Tristate.Value(true)`). `None`
    * means the emitter falls back to its standard default
    * (`Tristate.Absent` for non-required fields, no default for
    * required ones).
    */
  default: Option[String] = None
)

/** A description of a Scala type expression. The emitter walks this to
  * produce `scala.meta.Type` trees; we keep the model concrete and
  * library-agnostic so the planner has no scala.meta dependency.
  */
sealed trait ScalaType {

  /** Render as a Scala source fragment. Used for compact tests and for
    * inclusion in scaladoc strings; the emitter constructs proper
    * scala.meta trees rather than re-parsing this.
    */
  def render: String
}

object ScalaType {
  case object Str extends ScalaType { def render = "String" }
  case object I32 extends ScalaType { def render = "Int" }
  case object I64 extends ScalaType { def render = "Long" }
  case object Bool extends ScalaType { def render = "Boolean" }
  case object Dbl extends ScalaType { def render = "Double" }

  /** For endpoints whose only successful response is 204 No Content
    * (or any other body-less success). The emitter generates a method
    * returning `F[Unit]` and discards the empty response body without
    * a jsoniter codec lookup.
    */
  case object Unit extends ScalaType { def render = "Unit" }

  /** Reference to a named type, possibly imported from elsewhere
    * (`klaviyo4s-core`'s envelope types, or a sibling generated type
    * within the same category).
    */
  final case class Ref(qualifiedName: String) extends ScalaType {
    def render = qualifiedName
  }

  /** Generic application: `Vector[T]`, `Option[T]`, `Tristate[T]`. */
  final case class App(constructor: String, args: List[ScalaType]) extends ScalaType {
    def render = s"$constructor[${args.map(_.render).mkString(", ")}]"
  }
}

/** A generated method on the category's API class. */
final case class OperationPlan(
  /** camelCased method name, e.g. `getAccounts`. */
  scalaName: String,
  httpMethod: String,
  /** URL template with `{name}` placeholders preserved verbatim. */
  pathTemplate: String,
  parameters: List[ParamPlan],
  successType: ScalaType,
  summary: Option[String],
  description: Option[String],
  /** If the operation declares `x-klaviyo-filters`, the typed
    * filter builder spec. The emitter writes a sibling
    * `<OperationPascal>Filter` object alongside the API class.
    * `None` for endpoints without filter support.
    */
  filter: Option[FilterPlan] = None,
  /** Wire content type for the request body. `None` for body-less
    * operations. The two values the emitter cares about are:
    *
    *   - `"application/vnd.api+json"` (the JSON:API default; the
    *     emitter writes the body as a single JSON document).
    *   - `"multipart/form-data"` (only `upload_image_from_file`
    *     today; the emitter writes one multipart part per case-class
    *     field, with `format: binary` fields rendered as
    *     `multipartFile` parts).
    *
    * Falls back to whatever the spec declared if neither matches.
    */
  bodyContentType: Option[String] = None
)

/** Typed builder description for one operation's `x-klaviyo-filters`.
  *
  * Emitted as a per-operation Scala object containing one nested
  * object per filterable field, each exposing methods per legal
  * operator.
  */
final case class FilterPlan(
  /** Name of the generated object, e.g. `GetCampaignsFilter`. */
  objectName: String,
  fields: List[FilterFieldPlan]
)

/** One filterable field on an operation's filter set. */
final case class FilterFieldPlan(
  /** Klaviyo wire name verbatim — may contain dots
    * (`messages.channel`), which the emitter handles when emitting
    * the wire payload.
    */
  jsonName: String,
  /** Scala-legal identifier for the per-field nested object
    * (`messagesChannel`). Reserved words are not expected here
    * because Klaviyo's field names are snake_case-with-dots, but
    * the planner backticks on demand if a future spec drift
    * introduces one.
    */
  scalaName: String,
  /** How the operator renders the value. Drives which helper from
    * `Filter.{quoteString, renderBoolean, ...}` the emitter
    * inserts at the call site.
    */
  valueKind: FilterValueKind,
  /** Operators that take exactly one value. */
  singleOps: List[String],
  /** Operators that take a varargs list of values. */
  listOps: List[String],
  /** Operators that take no value (rare). */
  noneOps: List[String],
  /** Optional documented enum values for the value (for scaladoc). */
  enumValues: List[String]
)

/** Wire-rendering shape of a filter operator's value. */
sealed trait FilterValueKind

object FilterValueKind {

  /** Quoted string — values like `'Sent'` on the wire. */
  case object Str extends FilterValueKind

  /** Unquoted boolean — `true` / `false`. */
  case object Bool extends FilterValueKind

  /** Unquoted integer. */
  case object Int_ extends FilterValueKind

  /** Unquoted double. */
  case object Dbl extends FilterValueKind

  /** Quoted ISO-8601 datetime string. */
  case object DateTime extends FilterValueKind
}

/** A single parameter on an operation. */
final case class ParamPlan(
  scalaName: String,
  /** Wire name. For bracketed params like `fields[account]` this
    * preserves the brackets verbatim — the emitter restores them
    * when building the URL query string.
    */
  jsonName: String,
  location: ParamLocation,
  scalaType: ScalaType,
  required: Boolean,
  /** Scala literal source text for the default value, if any.
    * Currently only string defaults are captured (sufficient for the
    * `revision` header default).
    */
  defaultValue: Option[String],
  description: Option[String]
)

/** Where this parameter lives in the HTTP request.
  *
  * `Body` is the JSON payload of a POST/PATCH operation — the
  * emitter serialises it via jsoniter and attaches it to the sttp
  * request with `application/vnd.api+json` as the content type.
  * Klaviyo's specs always model the body via
  * `requestBody.content."application/vnd.api+json".schema`.
  */
sealed trait ParamLocation

object ParamLocation {
  case object Path extends ParamLocation
  case object Query extends ParamLocation
  case object Header extends ParamLocation
  case object Body extends ParamLocation
}
