package com.comraid.android.internal

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal class ComraidAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val id = ComraidScheduler.stringExtra(intent, ComraidScheduler.EXTRA_ID, ComraidScheduler.LEGACY_EXTRA_ID) ?: return
    val title = ComraidScheduler.stringExtra(intent, ComraidScheduler.EXTRA_TITLE, ComraidScheduler.LEGACY_EXTRA_TITLE) ?: return
    val body = ComraidScheduler.stringExtra(intent, ComraidScheduler.EXTRA_BODY, ComraidScheduler.LEGACY_EXTRA_BODY) ?: ""
    val channelId = ComraidScheduler.stringExtra(intent, ComraidScheduler.EXTRA_CHANNEL_ID, ComraidScheduler.LEGACY_EXTRA_CHANNEL_ID) ?: return
    val icon = ComraidScheduler.intExtra(intent, ComraidScheduler.EXTRA_SMALL_ICON, ComraidScheduler.LEGACY_EXTRA_SMALL_ICON, 0)
    if (icon == 0) return
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      data = Uri.Builder().scheme(ComraidScheduler.SCHEME).authority("notification").appendPath(id).build()
      putExtra(ComraidScheduler.EXTRA_ID, id)
      ComraidScheduler.dataPrefixKeys(intent)
        .forEach { key -> putExtra(key, intent.getStringExtra(key)) }
    }
    val contentIntent = launchIntent?.let {
      PendingIntent.getActivity(
        context,
        id.hashCode() and Int.MAX_VALUE,
        it,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }

    val notification = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(icon)
      .setContentTitle(title)
      .setContentText(body)
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setContentIntent(contentIntent)
      .build()

    val mayNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (mayNotify) {
      NotificationManagerCompat.from(context).notify(id, 0, notification)
    }
    ScheduleStore(context.applicationContext).remove(id)
  }
}
