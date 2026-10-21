package com.comraid.android.internal

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.comraid.android.ComRaidConfiguration
import com.comraid.android.ComRaidException
import com.comraid.android.ComRaidScheduleRequest
import com.comraid.android.ComRaidScheduleResult
import com.comraid.android.FallbackPolicy
import com.comraid.android.SchedulePrecision

internal class ComraidScheduler(private val context: Context) {
  private val alarmManager = context.getSystemService(AlarmManager::class.java)

  fun schedule(request: ComRaidScheduleRequest, configuration: ComRaidConfiguration): ComRaidScheduleResult {
    val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    val appliedPrecision: SchedulePrecision
    val fallbackReason: String?
    if (request.precision == SchedulePrecision.EXACT && !canExact) {
      if (request.fallbackPolicy == FallbackPolicy.FAIL) throw ComRaidException.ExactAlarmUnavailable()
      appliedPrecision = SchedulePrecision.INEXACT
      fallbackReason = "Exact alarm access is unavailable; scheduled as inexact"
    } else {
      appliedPrecision = request.precision
      fallbackReason = null
    }

    val intent = alarmIntent(request, configuration)
    if (appliedPrecision == SchedulePrecision.EXACT) {
      alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, request.triggerAtMillis, intent)
    } else {
      alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, request.triggerAtMillis, intent)
    }
    return ComRaidScheduleResult(
      id = request.notification.id,
      requestedPrecision = request.precision,
      appliedPrecision = appliedPrecision,
      triggerAtMillis = request.triggerAtMillis,
      fallbackReason = fallbackReason,
    )
  }

  fun cancel(id: String) {
    // Try new and legacy URI schemes so pre-migrate PendingIntents can still be cancelled.
    for (scheme in listOf(SCHEME, LEGACY_SCHEME)) {
      val intent = PendingIntent.getBroadcast(
        context,
        requestCode(id),
        Intent(context, ComraidAlarmReceiver::class.java).setData(uri(id, scheme)),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
      ) ?: continue
      alarmManager.cancel(intent)
      intent.cancel()
    }
  }

  private fun alarmIntent(request: ComRaidScheduleRequest, configuration: ComRaidConfiguration): PendingIntent {
    val notification = request.notification
    val intent = Intent(context, ComraidAlarmReceiver::class.java)
      .setData(uri(notification.id, SCHEME))
      .putExtra(EXTRA_ID, notification.id)
      .putExtra(EXTRA_TITLE, notification.title)
      .putExtra(EXTRA_BODY, notification.body)
      .putExtra(EXTRA_CHANNEL_ID, notification.channelId ?: configuration.defaultChannelId)
      .putExtra(EXTRA_SMALL_ICON, configuration.smallIconResourceId)
    notification.data.forEach { (key, value) -> intent.putExtra("$EXTRA_DATA_PREFIX$key", value) }
    return PendingIntent.getBroadcast(
      context,
      requestCode(notification.id),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun uri(id: String, scheme: String) =
    Uri.Builder().scheme(scheme).authority("schedule").appendPath(id).build()

  private fun requestCode(id: String) = id.hashCode() and Int.MAX_VALUE

  internal companion object {
    const val SCHEME = "comraid"
    const val LEGACY_SCHEME = "comraide"

    const val EXTRA_ID = "comraid.id"
    const val EXTRA_TITLE = "comraid.title"
    const val EXTRA_BODY = "comraid.body"
    const val EXTRA_CHANNEL_ID = "comraid.channelId"
    const val EXTRA_SMALL_ICON = "comraid.smallIcon"
    const val EXTRA_DATA_PREFIX = "comraid.data."

    const val LEGACY_EXTRA_ID = "comraide.id"
    const val LEGACY_EXTRA_TITLE = "comraide.title"
    const val LEGACY_EXTRA_BODY = "comraide.body"
    const val LEGACY_EXTRA_CHANNEL_ID = "comraide.channelId"
    const val LEGACY_EXTRA_SMALL_ICON = "comraide.smallIcon"
    const val LEGACY_EXTRA_DATA_PREFIX = "comraide.data."

    fun stringExtra(intent: Intent, key: String, legacyKey: String): String? =
      intent.getStringExtra(key) ?: intent.getStringExtra(legacyKey)

    fun intExtra(intent: Intent, key: String, legacyKey: String, default: Int): Int {
      if (intent.hasExtra(key)) return intent.getIntExtra(key, default)
      if (intent.hasExtra(legacyKey)) return intent.getIntExtra(legacyKey, default)
      return default
    }

    fun dataPrefixKeys(intent: Intent): List<String> {
      val keys = intent.extras?.keySet().orEmpty()
      return keys.filter { it.startsWith(EXTRA_DATA_PREFIX) || it.startsWith(LEGACY_EXTRA_DATA_PREFIX) }
    }
  }
}
