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
      sound = display.sound,
      badge = display.badge,
      threadId = display.threadId,
      androidChannelId = display.androidChannelId,
    )
  }
}
