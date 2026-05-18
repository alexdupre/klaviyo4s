package com.alexdupre.klaviyo

/** Klaviyo OpenAPI parser + Scala 3 code generator. Internal build tool;
  * not part of the published artifact set.
  *
  * Organized in three layers:
  *   - `codegen.spec` — Klaviyo-specific OpenAPI 3.0.2 parser. Reads a
  *     single category JSON into a typed `RawSpec`. Preserves
  *     `x-klaviyo-*` extensions; tolerates the spec's well-known
  *     non-conformances.
  *   - `codegen.model` — normalized plan (`KlaviyoCategoryPlan`). Resolves
  *     `\$ref`s, flattens `allOf`, lifts primitive `oneOf`s to sealed
  *     unions, detects JSON:API envelope schemas and rewrites responses,
  *     deduplicates against shared types from `klaviyo4s-core`.
  *   - `codegen.emit` — scala.meta tree builders for DTOs, jsoniter
  *     codecs and `XxxApi[F]` classes, followed by programmatic scalafmt
  *     against the project's `.scalafmt.conf`.
  */
package object codegen {}
