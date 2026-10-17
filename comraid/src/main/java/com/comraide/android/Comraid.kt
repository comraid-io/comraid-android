package com.comraide.android

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.comraide.android.internal.ComraidScheduler
import com.comraide.android.internal.ScheduleStore

object Comraid {
  private val lock = Any()
  @Volatile private var runtime: Runtime? = null

  fun configure(context: Context, configuration: ComraidConfiguration) {
    val appContext = context.applicationContext
    createDefaultChannel(appContext, configuration)
    synchronized(lock) {
      runtime = Runtime(configuration, ScheduleStore(appContext), ComraidScheduler(appContext))
    }
  }

  fun capabilities(context: Context): ComraidCapabilities {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val exactAvailable = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return ComraidCapabilities(
      notificationsEnabled = permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled(),
      exactAlarmsAvailable = exactAvailable,
    )
  }

  fun schedule(request: ComraidScheduleRequest): ComraidScheduleResult {
    val current = runtime ?: throw ComraidException.NotConfigured()
    require(request.notification.id.isNotBlank()) { "Notification id must not be blank" }
    if (request.triggerAtMillis <= System.currentTimeMillis()) {
      throw ComraidException.InvalidSchedule("triggerAtMillis must be in the future")
    }
    val result = current.scheduler.schedule(request, current.configuration)
    current.store.put(request.copy(precision = result.appliedPrecision))
    return result
  }

  fun startTimer(
    notification: ComraidNotification,
    durationMillis: Long,
    precision: SchedulePrecision = SchedulePrecision.INEXACT,
    fallbackPolicy: FallbackPolicy = FallbackPolicy.FAIL,
  ): ComraidScheduleResult {
    if (durationMillis <= 0) throw ComraidException.InvalidSchedule("durationMillis must be positive")
    return schedule(
      ComraidScheduleRequest(
        notification = notification,
        triggerAtMillis = System.currentTimeMillis() + durationMillis,
        precision = precision,
        fallbackPolicy = fallbackPolicy,
      )
    )
  }

  fun cancel(id: String) {
    val current = runtime ?: throw ComraidException.NotConfigured()
    current.scheduler.cancel(id)
    current.store.remove(id)
  }

  fun pendingSchedules(): List<ComraidScheduleRequest> =
    (runtime ?: throw ComraidException.NotConfigured()).store.all()

  internal fun restore(context: Context) {
    val current = runtime ?: return
    val now = System.currentTimeMillis()
    current.store.all().forEach { request ->
      if (request.triggerAtMillis > now) {
        runCatching { current.scheduler.schedule(request, current.configuration) }
      } else {
        current.store.remove(request.notification.id)
      }
    }
  }

  internal fun markDelivered(id: String) {
    runtime?.store?.remove(id)
  }

  private fun createDefaultChannel(context: Context, configuration: ComraidConfiguration) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = context.getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(
        NotificationChannel(
          configuration.defaultChannelId,
          configuration.defaultChannelName,
          NotificationManager.IMPORTANCE_DEFAULT,
        )
      )
    }
  }

  private data class Runtime(
    val configuration: ComraidConfiguration,
    val store: ScheduleStore,
    val scheduler: ComraidScheduler,
  )
}
