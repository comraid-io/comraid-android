package com.comraid.android.internal

import android.content.Context
import androidx.core.content.edit
import com.comraid.android.ComRaidNotification
import com.comraid.android.ComRaidScheduleRequest
import com.comraid.android.FallbackPolicy
import com.comraid.android.SchedulePrecision
import org.json.JSONArray
import org.json.JSONObject

internal class ScheduleStore(context: Context) {
  private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs ->
    migrateLegacyPrefs(context, prefs)
  }

  @Synchronized
  fun put(request: ComRaidScheduleRequest) {
    val schedules = all().associateByTo(linkedMapOf()) { it.notification.id }
    schedules[request.notification.id] = request
    write(schedules.values)
  }

  @Synchronized
  fun remove(id: String) {
    write(all().filterNot { it.notification.id == id })
  }

  @Synchronized
  fun all(): List<ComRaidScheduleRequest> {
    val raw = preferences.getString(KEY, null) ?: return emptyList()
    return runCatching {
      val array = JSONArray(raw)
      (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }.getOrElse { emptyList() }
  }

  private fun write(requests: Collection<ComRaidScheduleRequest>) {
    val array = JSONArray()
    requests.forEach { array.put(encode(it)) }
    preferences.edit { putString(KEY, array.toString()) }
  }

  private fun encode(request: ComRaidScheduleRequest) = JSONObject().apply {
    put("id", request.notification.id)
    put("title", request.notification.title)
    put("body", request.notification.body)
    put("channelId", request.notification.channelId)
    put("data", JSONObject(request.notification.data))
    put("triggerAtMillis", request.triggerAtMillis)
    put("precision", request.precision.name)
    put("fallbackPolicy", request.fallbackPolicy.name)
  }

  private fun decode(value: JSONObject): ComRaidScheduleRequest {
    val dataObject = value.optJSONObject("data") ?: JSONObject()
    val data = buildMap {
      dataObject.keys().forEach { key -> put(key, dataObject.getString(key)) }
    }
    return ComRaidScheduleRequest(
      notification = ComRaidNotification(
        id = value.getString("id"),
        title = value.getString("title"),
        body = value.getString("body"),
        channelId = value.optString("channelId").takeIf { it.isNotBlank() && it != "null" },
        data = data,
      ),
      triggerAtMillis = value.getLong("triggerAtMillis"),
      precision = SchedulePrecision.valueOf(value.getString("precision")),
      fallbackPolicy = FallbackPolicy.valueOf(value.getString("fallbackPolicy")),
    )
  }

  private companion object {
    const val KEY = "pending"
    const val PREFS_NAME = "comraid.schedules"
    const val LEGACY_PREFS_NAME = "comraide.schedules"

    fun migrateLegacyPrefs(context: Context, prefs: android.content.SharedPreferences) {
      if (prefs.contains(KEY)) return
      val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
      val legacyValue = legacy.getString(KEY, null) ?: return
      prefs.edit { putString(KEY, legacyValue) }
      legacy.edit { clear() }
    }
  }
}
