package com.comraid.android

/**
 * RNFirebase-shaped remote Notification event payload.
 * [data] holds sender keys only (ComRaid `cr_*` reserved keys stripped).
 * [attributionToken] is first-class so JS / presentNotification can ACK opened.
 */
data class RemoteMessageEvent(
  val messageId: String?,
  val deliveryId: String?,
  val attributionToken: String? = null,
  val title: String?,
  val body: String?,
  val data: Map<String, String>,
  val deepLink: String?,
  val actions: List<RemoteMessageAction>,
  val actionId: String? = null,
)

data class RemoteMessageAction(
  val id: String,
  val text: String,
  val icon: String? = null,
)
