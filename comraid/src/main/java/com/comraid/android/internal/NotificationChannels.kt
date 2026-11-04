package com.comraid.android.internal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.comraid.android.ComRaidConfiguration
import kotlin.concurrent.thread

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

  /**
   * Fetches Notification categories and creates their channels. Blocking network call:
   * never run it on the main thread (Android throws NetworkOnMainThreadException there).
   */
  fun syncCategories(context: Context, api: MobileApiClient): Boolean = runCatching {
    val categories = api.listNotificationCategories()
    if (categories.isNotEmpty()) ensureCategories(context, categories)
    true
  }.getOrDefault(false)

  /** [syncCategories] on a background thread, so `configure` can run from `Application.onCreate`. */
  fun syncCategoriesAsync(context: Context, api: MobileApiClient) {
    thread(name = "comraid-channel-sync", isDaemon = true) { syncCategories(context, api) }
  }

  /**
   * Channel to post on. When the payload names a channel this device never created (for example
   * the category sync failed), sync once more and otherwise fall back to the default channel:
   * on Android 8+ a notification posted to a missing channel is silently dropped.
   * Blocking; call from a background thread.
   */
  fun resolveChannelId(
    context: Context,
    configuration: ComRaidConfiguration,
    requested: String?,
  ): String {
    val fallback = configuration.defaultChannelId
    if (requested.isNullOrBlank()) return fallback
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return requested
    val manager = context.getSystemService(NotificationManager::class.java) ?: return fallback
    if (manager.getNotificationChannel(requested) != null) return requested
    val api = MobileApiClient(
      configuration.appId.trim(),
      UrlConnectionHttpTransport(configuration.apiBaseUrl),
    )
    syncCategories(context, api)
    return if (manager.getNotificationChannel(requested) != null) requested else fallback
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
          // Android fixes a channel's sound when it is first created; later edits need a new channel.
          rawSoundUri(context, category.androidSound)?.let { uri ->
            setSound(
              uri,
              AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            )
          }
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

  /** `android.resource://` URI for a host `res/raw` sound, or null when blank, `default`, or missing. */
  fun rawSoundUri(context: Context, sound: String?): Uri? {
    val name = PushPayload.soundResourceName(sound) ?: return null
    val resId = context.resources.getIdentifier(name, "raw", context.packageName)
    if (resId == 0) return null
    return Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$resId")
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
