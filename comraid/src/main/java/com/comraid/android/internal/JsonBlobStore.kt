package com.comraid.android.internal

import android.content.Context
import androidx.core.content.edit

/** Tiny string blob store used by schedule / timer persistence (and in-memory tests). */
internal interface JsonBlobStore {
  fun read(): String?
  fun write(value: String)
}

internal class PrefsJsonBlobStore(
  context: Context,
  prefsName: String,
  private val key: String,
) : JsonBlobStore {
  private val preferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

  override fun read(): String? = preferences.getString(key, null)

  override fun write(value: String) {
    preferences.edit { putString(key, value) }
  }
}

internal class InMemoryJsonBlobStore : JsonBlobStore {
  @Volatile private var value: String? = null

  override fun read(): String? = value

  override fun write(value: String) {
    this.value = value
  }
}
