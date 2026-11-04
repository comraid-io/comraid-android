package com.comraid.android.internal

import android.content.Context
import androidx.core.content.edit
import com.comraid.android.ComRaidConfiguration

/**
 * Persists configure() settings so FCM / ACK paths can run without a foreground
 * [com.comraid.android.ComRaid] singleton (process start from messaging service).
 */
internal class SdkConfigStore(context: Context) {
  private val preferences =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun save(configuration: ComRaidConfiguration) {
    preferences.edit {
      putString(KEY_APP_ID, configuration.appId.trim())
      putString(KEY_API_BASE, configuration.apiBaseUrl.trimEnd('/'))
      putString(KEY_CHANNEL_ID, configuration.defaultChannelId)
      putString(KEY_CHANNEL_NAME, configuration.defaultChannelName)
      putInt(KEY_SMALL_ICON, configuration.smallIconResourceId)
      putBoolean(KEY_DISPLAY_FOREGROUND, configuration.displayForegroundNotifications)
      val color = configuration.accentColor
      if (color != null) putInt(KEY_ACCENT_COLOR, color) else remove(KEY_ACCENT_COLOR)
    }
  }

  fun load(): ComRaidConfiguration? {
    val appId = preferences.getString(KEY_APP_ID, null)?.takeIf { it.isNotBlank() } ?: return null
    val apiBase = preferences.getString(KEY_API_BASE, null)?.takeIf { it.isNotBlank() }
      ?: ComRaidConfiguration.DEFAULT_API_BASE_URL
    val icon = preferences.getInt(KEY_SMALL_ICON, 0)
    if (icon == 0) return null
    return ComRaidConfiguration(
      appId = appId,
      apiBaseUrl = apiBase,
      defaultChannelId = preferences.getString(KEY_CHANNEL_ID, "comraid.general") ?: "comraid.general",
      defaultChannelName = preferences.getString(KEY_CHANNEL_NAME, "General") ?: "General",
      smallIconResourceId = icon,
      displayForegroundNotifications = preferences.getBoolean(KEY_DISPLAY_FOREGROUND, true),
      accentColor = if (preferences.contains(KEY_ACCENT_COLOR)) preferences.getInt(KEY_ACCENT_COLOR, 0) else null,
    )
  }

  private companion object {
    const val PREFS = "com.comraid.sdk_config"
    const val KEY_APP_ID = "app_id"
    const val KEY_API_BASE = "api_base_url"
    const val KEY_CHANNEL_ID = "default_channel_id"
    const val KEY_CHANNEL_NAME = "default_channel_name"
    const val KEY_SMALL_ICON = "small_icon"
    const val KEY_DISPLAY_FOREGROUND = "display_foreground_notifications"
    const val KEY_ACCENT_COLOR = "accent_color"
  }
}
