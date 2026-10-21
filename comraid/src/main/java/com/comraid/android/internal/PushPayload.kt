package com.comraid.android.internal

import org.json.JSONArray
import org.json.JSONObject

internal data class DeliveryAttribution(
  val deliveryId: String,
  val attributionToken: String,
)

internal data class PushAction(
  val id: String,
  val text: String,
  val icon: String? = null,
)

internal data class PushDisplay(
  val title: String,
  val body: String,
  val imageUrl: String? = null,
  val androidChannelId: String? = null,
)

internal object PushPayload {
  fun attribution(data: Map<String, String>?): DeliveryAttribution? {
    if (data == null) return null
    val deliveryId = data["cr_delivery_id"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val token = data["cr_attribution_token"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return DeliveryAttribution(deliveryId = deliveryId, attributionToken = token)
  }

  fun display(data: Map<String, String>?): PushDisplay {
    if (data == null) return PushDisplay(title = "", body = "")
    return PushDisplay(
      title = data["cr_title"].orEmpty(),
      body = data["cr_body"].orEmpty(),
      imageUrl = data["cr_image_url"]?.takeIf { it.isNotBlank() },
      androidChannelId = data["cr_android_channel_id"]?.takeIf { it.isNotBlank() },
    )
  }

  fun actions(data: Map<String, String>?): List<PushAction> {
    val raw = data?.get("cr_actions")?.takeIf { it.isNotBlank() } ?: return emptyList()
    return try {
      val array = JSONArray(raw)
      buildList {
        for (i in 0 until array.length()) {
          val obj = array.optJSONObject(i) ?: continue
          val id = obj.optString("id").trim()
          val text = obj.optString("text").trim()
          if (id.isEmpty() || text.isEmpty()) continue
          val icon = obj.optString("icon").trim().takeIf { it.isNotEmpty() }
          add(PushAction(id = id, text = text, icon = icon))
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun stringMap(data: Map<String, String>?): Map<String, String> = data.orEmpty()

  /** Convert FCM RemoteMessage data (values are strings) plus optional notification title/body. */
  fun fromFcmData(
    data: Map<String, String>,
    notificationTitle: String?,
    notificationBody: String?,
  ): Map<String, String> {
    val out = data.toMutableMap()
    if (!out.containsKey("cr_title") && !notificationTitle.isNullOrBlank()) {
      out["cr_title"] = notificationTitle
    }
    if (!out.containsKey("cr_body") && !notificationBody.isNullOrBlank()) {
      out["cr_body"] = notificationBody
    }
    return out
  }

  fun toJsonObject(data: Map<String, String>): JSONObject {
    val obj = JSONObject()
    data.forEach { (k, v) -> obj.put(k, v) }
    return obj
  }
}
