package com.alexdupre.klaviyo.core

import com.github.plokhotnyuk.jsoniter_scala.core.*

/** A piece of arbitrary JSON, carried opaquely through the codecs.
  *
  * Klaviyo's spec has several dozen `type: object` fields with no
  * `properties` declaration — places where the API accepts (or
  * returns) any JSON value the user chooses. Examples include
  * `TemplateRenderQueryResourceObject.attributes.context` (template
  * substitution variables), `ProfileResponseObjectResource.attributes
  * .properties` (custom profile properties), `EventResponseObjectResource
  * .attributes.event_properties` (arbitrary event payloads),
  * `FlowWebhook.headers`, `ImportErrorResponseObjectResource.attributes
  * .original_payload`, and others.
  *
  * The codegen renders all of those as `RawJson`. Codecs read and
  * write the raw bytes verbatim via jsoniter's
  * [[com.github.plokhotnyuk.jsoniter_scala.core.JsonReader.readRawValAsBytes]]
  * and [[com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter.writeRawVal]],
  * so nothing about the JSON structure is parsed or normalised on
  * either side — what came off the wire is what `text` returns, and
  * what the user passes to `RawJson.apply` is what goes on the wire.
  *
  * Construction:
  *   - `RawJson.apply` validates the input via `JsonReader.skip()`
  *     and throws `JsonReaderException` if it's not a single
  *     well-formed JSON value. This is the recommended path.
  *   - `RawJson.parse` returns the error as a value instead of
  *     throwing.
  *   - `RawJson.unchecked` skips validation — meaningful when the
  *     JSON came from a trusted source (another jsoniter encode, a
  *     known-good database column, an upstream API that already
  *     validated) and you want to avoid the extra full scan.
  */
opaque type RawJson = String

object RawJson {

  /** A no-op codec used only for its side effect: jsoniter's parser
    * loop runs `skip()` over the input, validating syntactic
    * well-formedness without building any AST.
    */
  private val validateCodec: JsonValueCodec[Unit] = new JsonValueCodec[Unit] {
    def decodeValue(in: JsonReader, default: Unit): Unit = in.skip()
    def encodeValue(x: Unit, out: JsonWriter): Unit = ()
    def nullValue: Unit = ()
  }

  /** Wrap a JSON-text string after verifying it's a single
    * well-formed JSON value with no trailing data. Throws
    * `JsonReaderException` on malformed input — the exception
    * message includes a byte offset and a hex/ascii dump pointing
    * at the offending token.
    */
  def apply(jsonText: String): RawJson = {
    readFromString[Unit](jsonText)(using validateCodec)
    jsonText
  }

  /** Validating constructor that returns the error as a value
    * instead of throwing. Use when the JSON comes from a
    * user-controlled source (form input, config file, …) and you'd
    * rather handle the failure inline than propagate a raw
    * `JsonReaderException` through your call chain.
    */
  def parse(jsonText: String): Either[String, RawJson] =
    try Right(apply(jsonText))
    catch { case e: JsonReaderException => Left(e.getMessage) }

  /** Skip validation. Use when the JSON came from a trusted source
    * and you want to avoid the extra scan — meaningful for the
    * larger fields (`original_payload`, bulk `kv_pairs`, …). If the
    * string is malformed, the surrounding document will be
    * corrupted at encode time.
    */
  def unchecked(jsonText: String): RawJson = jsonText

  /** The raw JSON text — exactly what came off the wire (for a
    * decoded value) or exactly what was passed to `RawJson.apply`
    * / `RawJson.parse` / `RawJson.unchecked` (for a value built in
    * user code).
    */
  extension (r: RawJson) def text: String = r

  given JsonValueCodec[RawJson] = new JsonValueCodec[RawJson] {
    def decodeValue(in: JsonReader, default: RawJson): RawJson = {
      val bs = in.readRawValAsBytes()
      new String(bs, java.nio.charset.StandardCharsets.UTF_8)
    }
    def encodeValue(x: RawJson, out: JsonWriter): Unit =
      out.writeRawVal(x.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    def nullValue: RawJson = null
  }
}
