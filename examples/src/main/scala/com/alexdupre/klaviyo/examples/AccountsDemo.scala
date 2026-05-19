package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*

/** Accounts category — read-only.
  *
  * The `accounts` endpoints don't mutate state and there's nothing to
  * clean up; we just list accounts and fetch the first one to confirm
  * the API key resolves and the request signing works end-to-end.
  */
object AccountsDemo extends DemoApp {

  def category: String = "accounts"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    section("list accounts") {
      val resp = client.accounts.getAccounts()
      log(s"got ${resp.data.size} account(s)")
      resp.data.foreach { a =>
        val tz = a.attributes.timezone
        val ind = a.attributes.industry.getOrElse("?")
        log(s"  id=${a.id} tz=$tz industry=$ind")
      }
    }

    section("fetch the first account by id") {
      client.accounts.getAccounts().data.headOption match {
        case None => log("no accounts visible to this key; skipping")
        case Some(first) =>
          val one = client.accounts.getAccount(first.id)
          log(s"getAccount(${one.data.id}) → industry=${one.data.attributes.industry.getOrElse("?")}")
      }
    }
  }
}
