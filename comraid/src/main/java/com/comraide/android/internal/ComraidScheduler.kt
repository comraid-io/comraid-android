package com.comraide.android.internal

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.comraide.android.ComraidConfiguration
import com.comraide.android.ComraidException
import com.comraide.android.ComraidScheduleRequest
import com.comraide.android.ComraidScheduleResult
import com.comraide.android.FallbackPolicy
import com.comraide.android.SchedulePrecision

internal class ComraidScheduler(private val context: Context) {
  private val alarmManager = context.getSystemService(AlarmManager::class.java)

  fun schedule(request: ComraidScheduleRequest, configuration: ComraidConfiguration): ComraidScheduleResult {
    val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    val appliedPrecision: SchedulePrecision
    val fallbackReason: String?
    if (request.precision == SchedulePrecision.EXACT && !canExact) {
      if (request.fallbackPolicy == FallbackPolicy.FAIL) throw ComraidException.ExactAlarmUnavailable()
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
    return ComraidScheduleResult(
      id = request.notification.id,
      requestedPrecision = request.precision,
      appliedPrecision = appliedPrecision,
      triggerAtMillis = request.triggerAtMillis,
      fallbackReason = fallbackReason,
    )
  }

  fun cancel(id: String) {
    val intent = PendingIntent.getBroadcast(
      context,
      requestCode(id),
      Intent(context, ComraidAlarmReceiver::class.java).setData(uri(id)),
      PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    ) ?: return
    alarmManager.cancel(intent)
    intent.cancel()
  }

  private fun alarmIntent(request: ComraidScheduleRequest, configuration: ComraidConfiguration): PendingIntent {
    val notification = request.notification
    val intent = Intent(context, ComraidAlarmReceiver::class.java)
      .setData(uri(notification.id))
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

  private fun uri(id: String) = Uri.Builder().scheme("comraide").authority("schedule").appendPath(id).build()
  private fun requestCode(id: String) = id.hashCode() and Int.MAX_VALUE

  internal companion object {
    const val EXTRA_ID = "comraide.id"
    const val EXTRA_TITLE = "comraide.title"
    const val EXTRA_BODY = "comraide.body"
    const val EXTRA_CHANNEL_ID = "comraide.channelId"
    const val EXTRA_SMALL_ICON = "comraide.smallIcon"
    const val EXTRA_DATA_PREFIX = "comraide.data."
  }
}
