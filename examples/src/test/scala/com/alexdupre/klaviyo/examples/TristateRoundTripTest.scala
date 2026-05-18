package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.core.Codecs.given
import com.alexdupre.klaviyo.models.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/** End-to-end Tristate round-trip on a real generated DTO.
  *
  * Picks `ProfileResponseObjectResource.Attributes` because it has
  * several `optional + nullable` fields where the distinction
  * between absent / null / value matters on the wire (Klaviyo's
  * PATCH endpoints interpret them differently).
  */
final class TristateRoundTripTest extends munit.FunSuite {

  // ProfileResponseObjectResource.Attributes is a generated DTO with
  // many Tristate-typed fields. We construct one for each state and
  // assert the wire shape.

  test("Tristate.Absent fields are omitted on the wire") {
    val attrs = ProfileResponseObjectResource.Attributes()  // all fields default to Absent
    val wire  = writeToString(attrs)
    assert(!wire.contains("email"),       s"email should be absent, got: $wire")
    assert(!wire.contains("phone_number"), s"phone_number should be absent, got: $wire")
  }

  test("Tristate.Null fields are written as JSON null") {
    val attrs = ProfileResponseObjectResource.Attributes(
      email        = Tristate.Null,
      phoneNumber  = Tristate.Null
    )
    val wire = writeToString(attrs)
    assert(wire.contains("\"email\":null"),        s"missing email:null in $wire")
    assert(wire.contains("\"phone_number\":null"), s"missing phone_number:null in $wire")
  }

  test("Tristate.Value fields are written as the underlying value") {
    val attrs = ProfileResponseObjectResource.Attributes(
      email       = "alice@example.com",   // implicit conversion lifts to Tristate.Value
      phoneNumber = "+15551234567"
    )
    val wire = writeToString(attrs)
    assert(wire.contains("\"email\":\"alice@example.com\""),    s"missing email in $wire")
    assert(wire.contains("\"phone_number\":\"+15551234567\""),  s"missing phone_number in $wire")
  }

  test("round-trip preserves all three states distinguishably") {
    val original = ProfileResponseObjectResource.Attributes(
      email       = "alice@example.com",
      phoneNumber = Tristate.Null
      // first_name is Absent (default)
    )
    val wire     = writeToString(original)
    val decoded  = readFromString[ProfileResponseObjectResource.Attributes](wire)

    assert(decoded.email.isValue,           s"email should round-trip as Value, got ${decoded.email}")
    assert(decoded.phoneNumber == Tristate.Null, s"phone_number should round-trip as Null, got ${decoded.phoneNumber}")
    assert(decoded.firstName == Tristate.Absent, s"first_name should round-trip as Absent, got ${decoded.firstName}")
  }
}
