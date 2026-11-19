package com.comraid.android.internal

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers which Live activities the user dismissed on this device, so a later update from the
 * server does not draw them again (even when the dismissal could not be reported yet).
 */
internal class LiveUpdateStore(context: Context) {
  private val preferences =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  @Synchronized
  fun markDismissed(activityId: String, nowMillis: Long = System.currentTimeMillis()) {
    preferences.edit {
      putLong(key(activityId), nowMillis)
      pruneOld(this, nowMillis)
    }
  }

  @Synchronized
  fun isDismissed(activityId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
    val at = preferences.getLong(key(activityId), 0L)
    return at > 0 && nowMillis - at < RETAIN_MILLIS
  }

  @Synchronized
  fun clearDismissed(activityId: String) {
    preferences.edit { remove(key(activityId)) }
  }

  /** The server ended this activity: a late or out-of-order update must not draw it again. */
  @Synchronized
  fun markEnded(activityId: String, nowMillis: Long = System.currentTimeMillis()) {
    preferences.edit {
      putLong(endedKey(activityId), nowMillis)
      pruneOld(this, nowMillis)
    }
  }

  @Synchronized
  fun isEnded(activityId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
    val at = preferences.getLong(endedKey(activityId), 0L)
    return at > 0 && nowMillis - at < RETAIN_MILLIS
  }

  @Synchronized
  fun clearEnded(activityId: String) {
    preferences.edit { remove(endedKey(activityId)) }
  }

  @Synchronized
  fun clearForTests() {
    preferences.edit { clear() }
  }

  /** An activity lives at most 8 hours, so a dismissal older than a day can never matter. */
  private fun pruneOld(editor: android.content.SharedPreferences.Editor, nowMillis: Long) {
    preferences.all.forEach { (k, v) ->
      if ((k.startsWith(PREFIX) || k.startsWith(ENDED_PREFIX)) && v is Long && nowMillis - v >= RETAIN_MILLIS) editor.remove(k)
    }
  }

  private fun key(activityId: String) = PREFIX + activityId

  private fun endedKey(activityId: String) = ENDED_PREFIX + activityId

  private companion object {
    const val PREFS = "com.comraid.live_updates"
    const val PREFIX = "dismissed:"
    const val ENDED_PREFIX = "ended:"
    const val RETAIN_MILLIS = 24L * 60 * 60 * 1000
  }
}
