package com.alexdupre.klaviyo.codegen.spec

import com.github.plokhotnyuk.jsoniter_scala.core._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Entry point of the codegen's spec layer.
  *
  * Reads a single Klaviyo OpenAPI category file into a typed
  * [[RawSpec]]. The parser is intentionally thin — all interesting
  * work (`\$ref` resolution, allOf flattening, JSON:API envelope
  * detection) happens one layer up in `model.Plan`.
  *
  * Errors from jsoniter (`JsonReaderException`) are surfaced verbatim
  * — they carry helpful diagnostics including the byte offset and a
  * hex/ASCII dump of the surrounding buffer, which is exactly what
  * a maintainer needs when a Klaviyo spec refresh introduces an
  * unexpected JSON shape.
  */
object Parser {

  /** Reader config tuned for Klaviyo's combined `stable.json`.
    *
    * The default `maxMapInsertNumber` (1024) is below Klaviyo's
    * actual schema count (~1200), so unboundng the parser was
    * preventing the codegen from reading the spec at all. We also
    * raise `preferredCharBufSize` slightly to reduce buffer
    * regrowth on the larger document.
    */
  private val readerConfig: ReaderConfig =
    ReaderConfig
      .withMaxBufSize(8 * 1024 * 1024) // ~8 MiB ceiling; stable.json is currently ~3 MiB
      .withPreferredCharBufSize(1 << 17)
      .withPreferredBufSize(1 << 17)
      .withMaxCharBufSize(8 * 1024 * 1024)

  /** Parse a spec from a file path. Convenience wrapper around
    * [[parseBytes]].
    */
  def parseFile(path: Path): RawSpec =
    readFromArray[RawSpec](Files.readAllBytes(path), readerConfig)

  /** Parse a spec from an in-memory JSON string. Used by unit tests
    * with synthetic fixtures.
    */
  def parseString(json: String): RawSpec =
    readFromString[RawSpec](json, readerConfig)

  /** Parse a spec from raw bytes. */
  def parseBytes(bytes: Array[Byte]): RawSpec =
    readFromArray[RawSpec](bytes, readerConfig)

  /** Convenience that also accepts a `String` argument. UTF-8 encoded. */
  def parseFromString(json: String): RawSpec =
    readFromArray[RawSpec](json.getBytes(StandardCharsets.UTF_8), readerConfig)
}
