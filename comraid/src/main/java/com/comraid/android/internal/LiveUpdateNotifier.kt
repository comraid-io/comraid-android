package com.comraid.android.internal

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.comraid.android.ComRaidConfiguration

/** Draws a Live activity as one ongoing notification per activity, updated in place. */
internal object LiveUpdateNotifier {
  const val CHANNEL_ID = "comraid.live_updates"
  private const val CHANNEL_NAME = "Live updates"

  /** Same id for every activity; the notification tag (the activity id) tells them apart. */
  const val NOTIFICATION_ID = 7301
  private const val EXTRA_ACTIVITY_TYPE = "com.comraid.android.extra.ACTIVITY_TYPE"
  private const val ACTION_DISMISSED = "com.comraid.android.action.LIVE_UPDATE_DISMISSED"

  /** Android 16: `Notification.ProgressStyle` and promoted live updates. */
  private const val PROGRESS_STYLE_API = 36
  private const val FINAL_STATE_MILLIS = 10L * 60 * 1000

  /** An activity lives at most 8 hours, so an ongoing notification never outlives that. */
  private const val MAX_LIFETIME_MILLIS = 8L * 60 * 60 * 1000

  fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
      android.app.NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
    )
  }

  /** Whether the app may post notifications at all (Android 13 asks at runtime). */
  fun canPost(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      return false
    }
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
  }

  fun showOngoing(context: Context, configuration: ComRaidConfiguration, message: LiveUpdate.Message) {
    val title = message.title ?: message.body ?: return
    if (!canPost(context)) return
    ensureChannel(context)
    val builder = baseBuilder(context, configuration, message, title)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setTimeoutAfter(MAX_LIFETIME_MILLIS)
      // Swiping an ongoing activity away is how a user dismisses it: tell ComRaid.
      .setDeleteIntent(dismissIntent(context, message.activityId))
    builder.extras.putString(EXTRA_ACTIVITY_TYPE, message.activityType)
    if (Build.VERSION.SDK_INT >= PROGRESS_STYLE_API) {
      // Android 16: the platform progress style and a request to be shown as a live update.
      val promoted = LiveUpdateApi36.promote(context, builder.build(), message.progress, message.statusText)
      NotificationManagerCompat.from(context).notify(message.activityId, NOTIFICATION_ID, promoted)
      return
    }
    message.progress?.let { builder.setProgress(100, it, false) }
    message.statusText?.let { builder.setSubText(it) }
    NotificationManagerCompat.from(context).notify(message.activityId, NOTIFICATION_ID, builder.build())
  }

  /** The last state of an ended activity: a normal notification the user can clear, gone after 10 minutes. */
  fun showFinal(context: Context, configuration: ComRaidConfiguration, message: LiveUpdate.Message) {
    val title = message.title ?: message.body ?: return cancel(context, message.activityId)
    if (!canPost(context)) return
    ensureChannel(context)
    val builder = baseBuilder(context, configuration, message, title)
      .setOngoing(false)
      .setAutoCancel(true)
      .setOnlyAlertOnce(true)
      .setTimeoutAfter(FINAL_STATE_MILLIS)
    message.statusText?.let { builder.setSubText(it) }
    NotificationManagerCompat.from(context).notify(message.activityId, NOTIFICATION_ID, builder.build())
  }

  private fun dismissIntent(context: Context, activityId: String): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      activityId.hashCode() and Int.MAX_VALUE,
      Intent(context, ComraidLiveUpdateReceiver::class.java)
        .setAction(ACTION_DISMISSED)
        // A PendingIntent's identity ignores extras: the id in the data keeps two activities' intents apart.
        .setData(android.net.Uri.parse("comraid-live-update://activity/" + android.net.Uri.encode(activityId)))
        .putExtra(ComraidLiveUpdateReceiver.EXTRA_ACTIVITY_ID, activityId),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

  /** Live activities showing right now: ongoing live updates only (a finished one is not running). */
  fun active(context: Context): List<com.comraid.android.ComRaidLiveActivity> {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
    return runCatching {
      manager.activeNotifications.mapNotNull { sbn ->
        val id = sbn.tag ?: return@mapNotNull null
        if (sbn.id != NOTIFICATION_ID) return@mapNotNull null
        if (sbn.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT == 0) return@mapNotNull null
        com.comraid.android.ComRaidLiveActivity(
          id = id,
          type = sbn.notification.extras.getString(EXTRA_ACTIVITY_TYPE).orEmpty(),
        )
      }
    }.getOrDefault(emptyList())
  }

  fun cancel(context: Context, activityId: String) {
    NotificationManagerCompat.from(context).cancel(activityId, NOTIFICATION_ID)
  }

  private fun baseBuilder(
    context: Context,
    configuration: ComRaidConfiguration,
    message: LiveUpdate.Message,
    title: String,
  ): NotificationCompat.Builder {
    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(configuration.smallIconResourceId)
      .setContentTitle(title)
      .setPriority(NotificationCompat.PRIORITY_LOW)
    message.body?.takeIf { message.title != null }?.let { builder.setContentText(it) }
    configuration.accentColor?.let { builder.setColor(it) }
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
      builder.setContentIntent(
        PendingIntent.getActivity(
          context,
          message.activityId.hashCode() and Int.MAX_VALUE,
          launch,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
      )
    }
    return builder
  }
}
