package com.comraide.android.internal

import android.content.Context
import androidx.core.content.edit
import com.comraide.android.ComraidNotification
import com.comraide.android.ComraidScheduleRequest
import com.comraide.android.FallbackPolicy
import com.comraide.android.SchedulePrecision
import org.json.JSONArray
import org.json.JSONObject

internal class ScheduleStore(context: Context) {
  private val preferences = context.getSharedPreferences("comraide.schedules", Context.MODE_PRIVATE)

  @Synchronized
  fun put(request: ComraidScheduleRequest) {
    val schedules = all().associateByTo(linkedMapOf()) { it.notification.id }
    schedules[request.notification.id] = request
    write(schedules.values)
  }

  @Synchronized
  fun remove(id: String) {
    write(all().filterNot { it.notification.id == id })
  }

  @Synchronized
  fun all(): List<ComraidScheduleRequest> {
    val raw = preferences.getString(KEY, null) ?: return emptyList()
    return runCatching {
      val array = JSONArray(raw)
      (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }.getOrElse { emptyList() }
  }

  private fun write(requests: Collection<ComraidScheduleRequest>) {
    val array = JSONArray()
    requests.forEach { array.put(encode(it)) }
    preferences.edit { putString(KEY, array.toString()) }
  }

  private fun encode(request: ComraidScheduleRequest) = JSONObject().apply {
    put("id", request.notification.id)
    put("title", request.notification.title)
    put("body", request.notification.body)
    put("channelId", request.notification.channelId)
    put("data", JSONObject(request.notification.data))
    put("triggerAtMillis", request.triggerAtMillis)
    put("precision", request.precision.name)
    put("fallbackPolicy", request.fallbackPolicy.name)
  }

  private fun decode(value: JSONObject): ComraidScheduleRequest {
    val dataObject = value.optJSONObject("data") ?: JSONObject()
    val data = buildMap {
      dataObject.keys().forEach { key -> put(key, dataObject.getString(key)) }
    }
    return ComraidScheduleRequest(
      notification = ComraidNotification(
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

  private companion object { const val KEY = "pending" }
}
