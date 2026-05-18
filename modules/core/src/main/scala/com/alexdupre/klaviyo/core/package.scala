package com.alexdupre.klaviyo

/** Shared, transport-agnostic types for the klaviyo4s family of modules.
  *
  * This package contains:
  *   - the JSON:API envelope types reused by every generated category
  *   - the [[KlaviyoError]] hierarchy (all extend `RuntimeException`)
  *   - the [[Tristate]] three-state field model that distinguishes
  *     `value` / `null` / omitted on the wire
  *   - [[KlaviyoConfig]] and the retry policy types
  *
  * Has no dependency on sttp or any HTTP transport — the runtime wiring
  * lives in `klaviyo4s-sttp`.
  */
package object core {}
