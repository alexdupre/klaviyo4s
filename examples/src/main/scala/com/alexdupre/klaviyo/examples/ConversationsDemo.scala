package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*

/** Conversations category — write-only and would send a real message.
  *
  * The only exposed endpoint is `createConversationMessage` which
  * publishes a real reply on the account's conversations inbox.
  * Skipped entirely unless `--side-effects` is set — and even then
  * we don't actually call it from the demo because the message
  * cannot be deleted afterwards and would be visible to the
  * account's CS team.
  */
object ConversationsDemo extends DemoApp {

  def category: String = "conversations"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    if (!sideEffectsEnabled) {
      log("conversations.createConversationMessage publishes a real reply on the account's inbox.")
      log("skipping — pass --side-effects to acknowledge, but the demo still won't call it")
      log("because the message can't be deleted afterwards.")
    } else {
      log("--side-effects on, but the conversations endpoint still won't be invoked.")
      log("publishing a CS reply would be visible to the account's support team.")
    }
  }
}
