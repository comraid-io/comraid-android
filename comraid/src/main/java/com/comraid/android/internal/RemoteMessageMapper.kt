package com.comraid.android.internal

import com.comraid.android.RemoteMessageAction
import com.comraid.android.RemoteMessageEvent

internal object RemoteMessageMapper {
  fun toEvent(data: Map<String, String>, actionId: String? = null): RemoteMessageEvent {
    val display = PushPayload.display(data)
    return RemoteMessageEvent(
      messageId = PushPayload.messageId(data),
      deliveryId = data["cr_delivery_id"]?.trim()?.takeIf { it.isNotEmpty() },
      attributionToken = data["cr_attribution_token"]?.trim()?.takeIf { it.isNotEmpty() },
      title = display.title.takeIf { it.isNotBlank() },
      body = display.body.takeIf { it.isNotBlank() },
      data = PushPayload.senderData(data),
      deepLink = PushPayload.deepLink(data),
      actions = PushPayload.actions(data).map {
        RemoteMessageAction(id = it.id, text = it.text, icon = it.icon)
      },
      actionId = actionId?.takeIf { it.isNotBlank() },
      imageUrl = display.imageUrl,
      subtitle = display.subtitle,
      sound = display.sound,
      badge = display.badge,
      threadId = display.threadId,
      androidChannelId = display.androidChannelId,
      priority = display.priority,
    )
  }

  /** Inverse of [toEvent] for presentNotification — rebuilds a payload map from the event. */
  fun toPayload(event: RemoteMessageEvent): Map<String, String> {
    val actionsJson = if (event.actions.isNotEmpty()) {
      org.json.JSONArray(
        event.actions.map { action ->
          org.json.JSONObject().apply {
            put("id", action.id)
            put("text", action.text)
            action.icon?.let { put("icon", it) }
          }
        },
      ).toString()
    } else {
      null
    }
    return buildMap {
      event.deliveryId?.let { put("cr_delivery_id", it) }
      event.attributionToken?.let { put("cr_attribution_token", it) }
      event.messageId?.let { put("cr_message_id", it) }
      event.title?.takeIf { it.isNotBlank() }?.let { put("cr_title", it) }
      put("cr_body", event.body.orEmpty())
      event.deepLink?.let { put("cr_deep_link", it) }
      actionsJson?.let { put("cr_actions", it) }
      event.imageUrl?.let { put("cr_image_url", it) }
      event.subtitle?.let { put("cr_subtitle", it) }
      event.sound?.let { put("cr_sound", it) }
      event.badge?.let { put("cr_badge", it.toString()) }
      event.threadId?.let { put("cr_thread_id", it) }
      event.androidChannelId?.let { put("cr_android_channel_id", it) }
      put("cr_priority", if (event.priority.trim().lowercase() == "normal") "normal" else "high")
      putAll(event.data)
    }
  }
}
