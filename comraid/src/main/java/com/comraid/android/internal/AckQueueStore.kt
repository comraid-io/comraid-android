package com.comraid.android.internal

import android.content.Context
import androidx.core.content.edit

internal interface AckQueueStore {
  fun get(key: String): String?
  fun set(key: String, value: String)
}

internal class SharedPreferencesAckQueueStore(context: Context) : AckQueueStore {
  private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  override fun get(key: String): String? = preferences.getString(key, null)

  override fun set(key: String, value: String) {
    preferences.edit { putString(key, value) }
  }

  private companion object {
    const val PREFS = "com.comraid.ack_queue"
  }
}
