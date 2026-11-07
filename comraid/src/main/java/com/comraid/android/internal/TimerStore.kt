package com.comraid.android.internal

import android.content.Context
import com.comraid.android.ComRaidNotification
import com.comraid.android.ComRaidTimer
import com.comraid.android.FallbackPolicy
import com.comraid.android.SchedulePrecision
import com.comraid.android.TimerState
import org.json.JSONArray
import org.json.JSONObject

internal class TimerStore(private val blob: JsonBlobStore) {
  constructor(context: Context) : this(PrefsJsonBlobStore(context, PREFS_NAME, KEY))

  @Synchronized
  fun put(timer: ComRaidTimer) {
    val timers = all().associateByTo(linkedMapOf()) { it.id }
    timers[timer.id] = timer
    write(timers.values)
  }

  @Synchronized
  fun remove(id: String) {
    write(all().filterNot { it.id == id })
  }

  @Synchronized
  fun all(): List<ComRaidTimer> {
    val raw = blob.read() ?: return emptyList()
    return runCatching {
      val array = JSONArray(raw)
      (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }.getOrElse { emptyList() }
  }

  @Synchronized
  fun markCompleted(id: String) {
    val timers = all()
    val index = timers.indexOfFirst { it.id == id }
    if (index < 0) return
    val current = timers[index]
    if (current.state == TimerState.CANCELLED || current.state == TimerState.COMPLETED) return
    put(
      current.copy(
        state = TimerState.COMPLETED,
        targetAtMillis = null,
        remainingDurationMillis = 0L,
      ),
    )
  }

  private fun write(timers: Collection<ComRaidTimer>) {
    val array = JSONArray()
    timers.forEach { array.put(encode(it)) }
    blob.write(array.toString())
  }

  private fun encode(timer: ComRaidTimer) = JSONObject().apply {
    put("id", timer.id)
    put("title", timer.notification.title)
    put("body", timer.notification.body)
    put("channelId", timer.notification.channelId)
    put("data", JSONObject(timer.notification.data))
    put("state", timer.state.name)
    put("targetAtMillis", timer.targetAtMillis ?: JSONObject.NULL)
    put("remainingDurationMillis", timer.remainingDurationMillis)
    put("precision", timer.precision.name)
    put("fallbackPolicy", timer.fallbackPolicy.name)
  }

  private fun decode(value: JSONObject): ComRaidTimer {
    val dataObject = value.optJSONObject("data") ?: JSONObject()
    val data = buildMap {
      dataObject.keys().forEach { key -> put(key, dataObject.getString(key)) }
    }
    val target = if (value.isNull("targetAtMillis")) null else value.getLong("targetAtMillis")
    return ComRaidTimer(
      id = value.getString("id"),
      notification = ComRaidNotification(
        id = value.getString("id"),
        title = value.getString("title"),
        body = value.getString("body"),
        channelId = value.optString("channelId").takeIf { it.isNotBlank() && it != "null" },
        data = data,
      ),
      state = TimerState.valueOf(value.getString("state")),
      targetAtMillis = target,
      remainingDurationMillis = value.getLong("remainingDurationMillis"),
      precision = SchedulePrecision.valueOf(value.optString("precision", SchedulePrecision.INEXACT.name)),
      fallbackPolicy = FallbackPolicy.valueOf(value.optString("fallbackPolicy", FallbackPolicy.FAIL.name)),
    )
  }

  private companion object {
    const val KEY = "timers"
    const val PREFS_NAME = "comraid.timers"
  }
}
