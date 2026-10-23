package com.comraid.android.internal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.comraid.android.ComRaidConfiguration

internal object NotificationChannels {
  fun ensureDefault(context: Context, configuration: ComRaidConfiguration) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
      NotificationChannel(
        configuration.defaultChannelId,
        configuration.defaultChannelName,
        NotificationManager.IMPORTANCE_DEFAULT,
      ),
    )
  }

  fun ensureCategories(context: Context, categories: List<MobileApiClient.NotificationCategory>) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    categories.forEach { category ->
      manager.createNotificationChannel(
        NotificationChannel(
          category.androidChannelId,
          category.name,
          importance(category.androidImportance),
        ).apply {
          enableLights(category.androidLights)
          when (val vib = category.androidVibration) {
            false -> vibrationPattern = longArrayOf()
            is org.json.JSONArray -> {
              val pattern = buildList {
                for (i in 0 until vib.length()) {
                  if (!vib.isNull(i)) add(vib.getLong(i))
                }
              }.toLongArray()
              if (pattern.isNotEmpty()) vibrationPattern = pattern
            }
            is List<*> -> {
              val pattern = vib.mapNotNull { (it as? Number)?.toLong() }.toLongArray()
              if (pattern.isNotEmpty()) vibrationPattern = pattern
            }
          }
        },
      )
    }
  }

  private fun importance(value: String): Int = when (value.lowercase()) {
    "none" -> NotificationManager.IMPORTANCE_NONE
    "min" -> NotificationManager.IMPORTANCE_MIN
    "low" -> NotificationManager.IMPORTANCE_LOW
    "high" -> NotificationManager.IMPORTANCE_HIGH
    "max" -> NotificationManager.IMPORTANCE_MAX
    else -> NotificationManager.IMPORTANCE_DEFAULT
  }
}
