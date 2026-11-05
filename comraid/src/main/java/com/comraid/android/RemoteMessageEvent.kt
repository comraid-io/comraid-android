package com.comraid.android

/**
 * RNFirebase-shaped remote Notification event payload.
 * [data] holds sender keys only (ComRaid `cr_*` reserved keys stripped).
 * [attributionToken] is first-class so JS / presentNotification can ACK opened.
 * [imageUrl] / [sound] / [badge] / [threadId] / [androidChannelId] are first-class so
 * presentNotification keeps them.
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
  val imageUrl: String? = null,
  val sound: String? = null,
  val badge: Int? = null,
  val threadId: String? = null,
  val androidChannelId: String? = null,
)

data class RemoteMessageAction(
  val id: String,
  val text: String,
  val icon: String? = null,
)
