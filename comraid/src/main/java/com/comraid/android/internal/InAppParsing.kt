package com.comraid.android.internal

import com.comraid.android.InAppButton
import com.comraid.android.InAppLayout
import com.comraid.android.InAppMessage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** A fetched In-app message with the Delivery credentials its ACKs need (absent for a Test send). */
internal data class FetchedInAppMessage(
  val message: InAppMessage,
  val deliveryId: String?,
  val attributionToken: String?,
)

private val RFC3339 = Regex(
  """^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:\d{2})$""",
)

/**
 * Parse an RFC 3339 instant to epoch milliseconds, or null when it is not one. Written without
 * `java.time` and `SimpleDateFormat`'s `X` pattern, which need API 26 and 24 (minSdk is 23).
 */
internal fun parseRfc3339(value: String?): Long? {
  val match = RFC3339.matchEntire(value?.trim().orEmpty()) ?: return null
  val (year, month, day, hour, minute, second, zone) = match.destructured
  val calendar = GregorianCalendar(TimeZone.getTimeZone("GMT"))
  calendar.isLenient = false
  return try {
    calendar.clear()
    calendar.set(year.toInt(), month.toInt() - 1, day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
    val utcMillis = calendar.timeInMillis
    val offsetMillis = if (zone == "Z") {
      0L
    } else {
      val sign = if (zone[0] == '-') -1 else 1
      sign * (zone.substring(1, 3).toLong() * 60 + zone.substring(4, 6).toLong()) * 60_000L
    }
    utcMillis - offsetMillis
  } catch (_: IllegalArgumentException) {
    null
  }
}

/** Parse the `messages` array of GET /in-app-messages, dropping any message the SDK cannot draw. */
internal fun parseInAppMessages(messages: JSONArray?): List<FetchedInAppMessage> {
  if (messages == null) return emptyList()
  return buildList {
    for (i in 0 until messages.length()) {
      val obj = messages.optJSONObject(i) ?: continue
      parseInAppMessage(obj)?.let { add(it) }
    }
  }
}

private fun parseInAppMessage(obj: JSONObject): FetchedInAppMessage? {
  val id = obj.optString("id").trim().takeIf { it.isNotEmpty() } ?: return null
  val layout = InAppLayout.fromWire(obj.optString("layout")) ?: return null
  val buttons = buildList {
    val array = obj.optJSONArray("buttons") ?: return@buildList
    for (i in 0 until array.length()) {
      val button = array.optJSONObject(i) ?: continue
      val buttonId = button.optString("id").trim().takeIf { it.isNotEmpty() } ?: continue
      add(
        InAppButton(
          id = buttonId,
          label = button.optString("label"),
          deepLink = button.optString("deep_link").trim().takeIf { it.isNotEmpty() },
        ),
      )
    }
  }
  val isTest = obj.optBoolean("test", false)
  val deliveryId = obj.optString("delivery_id").trim().takeIf { it.isNotEmpty() }
  val token = obj.optString("attribution_token").trim().takeIf { it.isNotEmpty() }
  return FetchedInAppMessage(
    message = InAppMessage(
      id = id,
      layout = layout,
      title = obj.optString("title"),
      body = obj.optString("body"),
      imageUrl = obj.optString("image_url").trim().takeIf { it.isNotEmpty() },
      buttons = buttons,
      startAtMillis = parseRfc3339(obj.optString("start_at")),
      endAtMillis = parseRfc3339(obj.optString("end_at")),
      isTest = isTest,
    ),
    // A Test send has no Delivery; never ACK it even if credentials were somehow present.
    deliveryId = if (isTest) null else deliveryId,
    attributionToken = if (isTest) null else token,
  )
}

/** Cache form of a fetched message: like the wire form, but with the window already in millis. */
internal fun FetchedInAppMessage.toCacheJson(): JSONObject {
  val buttonsJson = JSONArray()
  for (button in message.buttons) {
    buttonsJson.put(
      JSONObject()
        .put("id", button.id)
        .put("label", button.label)
        .apply { button.deepLink?.let { put("deep_link", it) } },
    )
  }
  return JSONObject()
    .put("id", message.id)
    .put("layout", message.layout.wire)
    .put("title", message.title)
    .put("body", message.body)
    .put("buttons", buttonsJson)
    .put("test", message.isTest)
    .apply {
      message.imageUrl?.let { put("image_url", it) }
      message.startAtMillis?.let { put("start_ms", it) }
      message.endAtMillis?.let { put("end_ms", it) }
      deliveryId?.let { put("delivery_id", it) }
      attributionToken?.let { put("attribution_token", it) }
    }
}

internal fun parseCachedInAppMessages(array: JSONArray?): List<FetchedInAppMessage> {
  if (array == null) return emptyList()
  return buildList {
    for (i in 0 until array.length()) {
      val obj = array.optJSONObject(i) ?: continue
      val parsed = parseInAppMessage(obj) ?: continue
      add(
        parsed.copy(
          message = parsed.message.copy(
            startAtMillis = if (obj.has("start_ms")) obj.optLong("start_ms") else null,
            endAtMillis = if (obj.has("end_ms")) obj.optLong("end_ms") else null,
          ),
        ),
      )
    }
  }
}
