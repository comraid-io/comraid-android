package com.comraid.android.internal

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

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
  val subtitle: String? = null,
  val androidChannelId: String? = null,
  val sound: String? = null,
  val badge: Int? = null,
  /** Android notification tag: a newer push with the same thread replaces the older one. */
  val threadId: String? = null,
  val highPriority: Boolean = true,
) {
  /** `"high"` or `"normal"` for receive-event parity (ADR 0012). */
  val priority: String get() = if (highPriority) "high" else "normal"
}

internal object PushPayload {
  /** Reserved `cr_*` keys — stripped from the RN/event `data` map so sender keys stay unchanged. */
  private val reservedKeys = setOf(
    "cr_delivery_id",
    "cr_attribution_token",
    "cr_message_id",
    "cr_message_recipient_id",
    "cr_title",
    "cr_body",
    "cr_image_url",
    "cr_subtitle",
    "cr_android_channel_id",
    "cr_deep_link",
    "cr_actions",
    "cr_data_only",
    "cr_sound",
    "cr_badge",
    "cr_thread_id",
    "cr_priority",
  )

  fun attribution(data: Map<String, String>?): DeliveryAttribution? {
    if (data == null) return null
    val deliveryId = data["cr_delivery_id"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val token = data["cr_attribution_token"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return DeliveryAttribution(deliveryId = deliveryId, attributionToken = token)
  }

  fun isDataOnly(data: Map<String, String>?): Boolean {
    val raw = data?.get("cr_data_only")?.trim()?.lowercase() ?: return false
    return raw == "true" || raw == "1"
  }

  fun messageId(data: Map<String, String>?): String? =
    data?.get("cr_message_id")?.trim()?.takeIf { it.isNotEmpty() }

  /** Sender `data` keys only — ComRaid reserved `cr_*` keys removed. */
  fun senderData(data: Map<String, String>?): Map<String, String> {
    if (data == null) return emptyMap()
    return data.filterKeys { key -> key !in reservedKeys && !key.startsWith("cr_") }
  }

  /** Pack payload into launch-intent extras for system-tray / trampoline opens. */
  fun openIntentExtras(data: Map<String, String>): Map<String, String> = data.toMap()

  /** Restore payload map from launch-intent string extras (ignores non-string extras). */
  fun fromIntentExtras(extras: Map<String, String?>): Map<String, String> =
    extras.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

  fun display(data: Map<String, String>?): PushDisplay {
    if (data == null) return PushDisplay(title = "", body = "")
    return PushDisplay(
      title = data["cr_title"].orEmpty(),
      body = data["cr_body"].orEmpty(),
      imageUrl = data["cr_image_url"]?.takeIf { it.isNotBlank() },
      subtitle = data["cr_subtitle"]?.trim()?.takeIf { it.isNotEmpty() },
      androidChannelId = data["cr_android_channel_id"]?.takeIf { it.isNotBlank() },
      sound = data["cr_sound"]?.trim()?.takeIf { it.isNotEmpty() },
      badge = data["cr_badge"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
      threadId = data["cr_thread_id"]?.trim()?.takeIf { it.isNotEmpty() },
      highPriority = data["cr_priority"]?.trim()?.lowercase() != "normal",
    )
  }

  /**
   * `res/raw` resource name for a sound (`chime.wav` → `chime`), matching FCM `android.notification.sound`.
   * Returns null for blank or `default`, which keep the channel / system sound.
   */
  fun soundResourceName(raw: String?): String? {
    val name = raw?.trim()?.substringBeforeLast('.')?.takeIf { it.isNotEmpty() } ?: return null
    return if (name.equals("default", ignoreCase = true)) null else name
  }

  /**
   * First-class deep link from API `content.push.deep_link`, delivered as `cr_deep_link`.
   * Returns null when missing, blank, or not a scheme-bearing URI.
   */
  fun deepLink(data: Map<String, String>?): String? {
    val raw = data?.get("cr_deep_link")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
      val uri = URI(raw)
      if (uri.scheme.isNullOrBlank()) null else raw
    }.getOrNull()
  }

  /**
   * URI for SDK-built tray content intent: prefer [deepLink], else attribution-only open.
   */
  fun contentOpenUri(deliveryId: String, data: Map<String, String>?): String =
    deepLink(data) ?: "comraid://open/$deliveryId"


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
