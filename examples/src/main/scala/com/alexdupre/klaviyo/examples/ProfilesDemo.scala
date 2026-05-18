package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*

/** Profiles category — read by default, create-and-purge under
  * `--side-effects`.
  *
  * Klaviyo's profile API has no delete endpoint; the only way to
  * remove a profile is `dataprivacy.requestProfileDeletion`, which
  * is asynchronous (the data is purged on Klaviyo's side over the
  * following minutes) and GDPR-shaped. When `--side-effects` is on
  * we create a synthetic profile under a non-deliverable
  * `…@example.invalid` address, exercise update + lookups, and file
  * the deletion request as cleanup. Without `--side-effects` we
  * stick to list / get.
  *
  * The synthetic address uses the `*.invalid` reserved TLD (RFC 6761)
  * so even if the cleanup deletion request is delayed, no message
  * sent to it could land anywhere real.
  */
object ProfilesDemo extends DemoApp {

  def category: String = "profiles"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    section("list profiles (first page)") {
      val resp = client.profiles.getProfiles()
      log(s"got ${resp.data.size} profile(s)")
    }

    section("fetch first profile by id (if any)") {
      val resp = client.profiles.getProfiles()
      resp.data.headOption.flatMap(_.id.toOption) match {
        case None => log("account has no profiles; skipping getProfile")
        case Some(pid) =>
          val one = client.profiles.getProfile(pid)
          log(
            s"profile id=${one.data.id.toOption.getOrElse("?")} email=${one.data.attributes.email.toOption.getOrElse("?")}"
          )
      }
    }

    if (!sideEffectsEnabled) {
      log("(set --side-effects to create+update a profile; cleanup uses dataprivacy.requestProfileDeletion)")
      return
    }

    // Synthetic profile under .invalid TLD — guarantees the email
    // cannot be delivered to a real person.
    val email = s"demo-$rand@klaviyo4s.invalid"

    section("create profile (side-effects)") {
      val resp = client.profiles.createProfile(
        ProfileCreateQuery(
          data = ProfileCreateQueryResourceObject(
            attributes = ProfileCreateQueryResourceObject.Attributes(
              email = email,
              firstName = "klaviyo4s",
              lastName = "demo"
            )
          )
        )
      )
      val profileId = resp.data.id.toOption.getOrElse(throw new IllegalStateException("createProfile returned no id"))
      register(s"requestProfileDeletion($email)") {
        client.dataprivacy.requestProfileDeletion(
          DataPrivacyCreateDeletionJobQuery(
            data = DataPrivacyCreateDeletionJobQueryResourceObject(
              attributes = DataPrivacyCreateDeletionJobQueryResourceObject.Attributes(
                profile = DataPrivacyCreateDeletionJobQueryResourceObject.Attributes.Profile(
                  data = DataPrivacyProfileQueryResourceObject(
                    attributes = DataPrivacyProfileQueryResourceObject.Attributes(email = email)
                  )
                )
              )
            )
          )
        )
      }
      log(s"created profile id=$profileId email=$email")

      // Re-fetch + update.
      val fetched = client.profiles.getProfile(profileId)
      log(s"  initial firstName=${fetched.data.attributes.firstName.toOption.getOrElse("?")}")

      client.profiles.updateProfile(
        id = profileId,
        body = ProfilePartialUpdateQuery(
          data = ProfilePartialUpdateQueryResourceObject(
            id = profileId,
            attributes = ProfilePartialUpdateQueryResourceObject.Attributes(
              firstName = "klaviyo4s-updated"
            )
          )
        )
      )
      log("  firstName updated")
    }
  }
}
