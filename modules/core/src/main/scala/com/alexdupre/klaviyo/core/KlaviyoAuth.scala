package com.alexdupre.klaviyo.core

/** Authentication strategy. Sealed because Klaviyo recognises only a
  * fixed set of credential shapes; future OAuth-bearer support will add
  * an additional case without breaking existing callers.
  */
sealed trait KlaviyoAuth

object KlaviyoAuth {

  /** Private API key sent in the `Authorization` header as
    * `Klaviyo-API-Key <key>`. This is the dominant Klaviyo auth scheme
    * for server-to-server integrations.
    */
  final case class PrivateKey(value: String) extends KlaviyoAuth
}
