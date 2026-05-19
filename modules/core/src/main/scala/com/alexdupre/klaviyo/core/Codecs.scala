package com.alexdupre.klaviyo.core

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import scala.annotation.targetName

/** Primitive `JsonValueCodec` instances required for `Tristate[A]` codec
  * derivation to work.
  *
  * Background: jsoniter-scala's macro emits inline code for primitive
  * field types (`String`, `Int`, ...) and does NOT define implicit
  * `JsonValueCodec` instances for them. This is fine for plain fields,
  * but our parameterised `Tristate.codec[A](using inner: JsonValueCodec[A])`
  * needs to be summoned by the macro when it encounters a field of type
  * `Tristate[String]`. Summoning requires `JsonValueCodec[String]` to
  * resolve, and without a given for `String` the macro silently falls
  * back to deriving a discriminator-based ADT codec for `Tristate`
  * itself — which is exactly the encoding we are trying to avoid.
  *
  * Importing this object (`import com.alexdupre.klaviyo.core.Codecs.given`)
  * provides the necessary primitives. The code generator emits this import
  * in every generated DTO file; hand-written modules import it where
  * needed.
  */
object Codecs {

  // Primitive codecs.
  //
  // Klaviyo's spec marks many fields as required + non-nullable but
  // the actual API sometimes returns `null` for them (e.g.
  // `ImageResponseObjectResourceAttributes.name` is wire-null on
  // system-uploaded images). Strict jsoniter macro-generated codecs
  // throw on null in those positions, breaking the whole response
  // decode. These hand-rolled codecs absorb a wire `null` and return
  // the type's safe default — empty string for `String`, zero for
  // numeric types, false for `Boolean`. The behaviour is lossy
  // (caller can't distinguish "wire returned null" from "wire
  // returned empty / zero / false") but unblocks decode for every
  // field where the spec under-declares nullability.
  //
  // Tristate fields aren't affected: the Tristate codec handles
  // `null` itself (returning `Tristate.Null` for nullable Tristate
  // states) before the inner primitive codec is ever invoked.

  /** Shared "swallow wire-null, otherwise decode normally" routine for
    * the primitive codecs below. Each codec uses the same shape — peek
    * for an `n` token, consume the null literal returning the type's
    * safe default, else roll back and let the reader's primitive
    * decode do its thing. Inlined so there's no closure allocation on
    * the decode hot path.
    */
  private inline def nullTolerant[A](in: JsonReader, fallback: A, msg: String)(inline read: => A): A =
    if (in.isNextToken('n')) { in.readNullOrError(fallback, msg); fallback }
    else { in.rollbackToken(); read }

  given JsonValueCodec[String] = new JsonValueCodec[String] {
    def decodeValue(in: JsonReader, default: String): String =
      nullTolerant(in, "", "expected string or null")(in.readString(""))
    def encodeValue(x: String, out: JsonWriter): Unit = out.writeVal(x)
    def nullValue: String = ""
  }

  given JsonValueCodec[Int] = new JsonValueCodec[Int] {
    def decodeValue(in: JsonReader, default: Int): Int =
      nullTolerant(in, 0, "expected int or null")(in.readInt())
    def encodeValue(x: Int, out: JsonWriter): Unit = out.writeVal(x)
    def nullValue: Int = 0
  }

  given JsonValueCodec[Long] = new JsonValueCodec[Long] {
    def decodeValue(in: JsonReader, default: Long): Long =
      nullTolerant(in, 0L, "expected long or null")(in.readLong())
    def encodeValue(x: Long, out: JsonWriter): Unit = out.writeVal(x)
    def nullValue: Long = 0L
  }

  given JsonValueCodec[Boolean] = new JsonValueCodec[Boolean] {
    def decodeValue(in: JsonReader, default: Boolean): Boolean =
      nullTolerant(in, false, "expected boolean or null")(in.readBoolean())
    def encodeValue(x: Boolean, out: JsonWriter): Unit = out.writeVal(x)
    def nullValue: Boolean = false
  }

  given JsonValueCodec[Double] = new JsonValueCodec[Double] {
    def decodeValue(in: JsonReader, default: Double): Double =
      nullTolerant(in, 0.0, "expected double or null")(in.readDouble())
    def encodeValue(x: Double, out: JsonWriter): Unit = out.writeVal(x)
    def nullValue: Double = 0.0
  }

  given JsonValueCodec[Float] = new JsonValueCodec[Float] {
    def decodeValue(in: JsonReader, default: Float): Float =
      nullTolerant(in, 0.0f, "expected float or null")(in.readFloat())
    def encodeValue(x: Float, out: JsonWriter): Unit = out.writeVal(x)
    def nullValue: Float = 0.0f
  }

  // Common composite types Klaviyo's specs reference frequently.
  //
  // The `Vector[...]` instances exist specifically to make
  // `Tristate[Vector[T]]` fields work for primitive T. Jsoniter's
  // macro generates Vector handling inline and never publishes a
  // `JsonValueCodec[Vector[X]]`, which leaves nothing for the
  // parameterised `Tristate.codec[A](using JsonValueCodec[A])` to
  // compose with. Providing explicit instances for the primitive
  // element types closes the gap.
  //
  // For generated case-class / sealed-trait element types, the
  // codegen emits a sibling `given JsonValueCodec[Vector[T]]` in
  // each type's companion — see `Emitter.scala`.
  // `@targetName` is required because anonymous `given`s on
  // parameterised types like `Vector[X]` mangle to the same JVM-level
  // name regardless of `X`. Without explicit target names Scala 3
  // rejects the file with "two definitions cannot have the same
  // bytecode name". The names are not referenced by user code.
  @targetName("givenJsonValueCodecVectorString")
  given JsonValueCodec[Vector[String]] = JsonCodecMaker.make

  @targetName("givenJsonValueCodecVectorInt")
  given JsonValueCodec[Vector[Int]] = JsonCodecMaker.make

  @targetName("givenJsonValueCodecVectorLong")
  given JsonValueCodec[Vector[Long]] = JsonCodecMaker.make

  @targetName("givenJsonValueCodecVectorBoolean")
  given JsonValueCodec[Vector[Boolean]] = JsonCodecMaker.make

  @targetName("givenJsonValueCodecVectorDouble")
  given JsonValueCodec[Vector[Double]] = JsonCodecMaker.make

  @targetName("givenJsonValueCodecVectorFloat")
  given JsonValueCodec[Vector[Float]] = JsonCodecMaker.make

  given JsonValueCodec[List[String]] = JsonCodecMaker.make
  given JsonValueCodec[Map[String, String]] = JsonCodecMaker.make

  // Codecs for OpenAPI `string` schemas with a `format`. These are
  // needed in implicit scope for two reasons:
  //
  //   1. `Tristate.codec[A](using JsonValueCodec[A])` looks up the
  //      inner type's codec at definition time. Without explicit
  //      givens, a field of type `Tristate.Optional[LocalDate]`
  //      would fail to summon.
  //   2. Record-level macro derivations (`JsonCodecMaker.make[T]`
  //      where T has a `LocalDate`/`OffsetDateTime`/`URI` field)
  //      benefit from sharing the same codec instance rather than
  //      inlining a fresh one at every site.
  //
  // jsoniter-scala's macro handles each of these types natively, so
  // the `JsonCodecMaker.make` call below derives the appropriate
  // ISO-8601 (for time types) and string-backed (for URI) codec
  // without any extra configuration.
  given JsonValueCodec[java.time.LocalDate] = JsonCodecMaker.make
  given JsonValueCodec[java.time.OffsetDateTime] = JsonCodecMaker.make

  // jsoniter-scala's macro does NOT include a built-in codec for
  // `java.net.URI`, even though it does for several other JDK
  // primitives. The wire form is just the URI's `toString` — there's
  // no ambiguity to resolve at decode time, so a String-passthrough
  // codec is sufficient and matches what `JsonCodecMaker.make` would
  // produce if it supported the type.
  given JsonValueCodec[java.net.URI] = new JsonValueCodec[java.net.URI] {
    def decodeValue(in: JsonReader, default: java.net.URI): java.net.URI =
      java.net.URI.create(in.readString(null))
    def encodeValue(x: java.net.URI, out: JsonWriter): Unit = out.writeVal(x.toString)
    def nullValue: java.net.URI = null
  }
}
