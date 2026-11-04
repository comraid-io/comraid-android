package com.comraid.android.internal

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.comraid.android.ComRaidConfiguration
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

internal object RemoteNotificationDisplay {
  private const val IMAGE_TIMEOUT_MS = 10_000
  private const val IMAGE_MAX_BYTES = 5 * 1024 * 1024
  private const val IMAGE_MAX_EDGE_PX = 1440

  /**
   * Posts an SDK-drawn tray item. Downloading the image and re-syncing channels are network I/O,
   * so calls from the main thread (for example `presentNotification`) hop to a background thread.
   */
  fun show(
    context: Context,
    configuration: ComRaidConfiguration,
    data: Map<String, String>,
  ) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      val appContext = context.applicationContext
      thread(name = "comraid-notification", isDaemon = true) {
        runCatching { showBlocking(appContext, configuration, data) }
      }
    } else {
      showBlocking(context, configuration, data)
    }
  }

  private fun showBlocking(
    context: Context,
    configuration: ComRaidConfiguration,
    data: Map<String, String>,
  ) {
    val display = PushPayload.display(data)
    val title = display.title.ifBlank { return }
    val body = display.body
    val channelId = NotificationChannels.resolveChannelId(context, configuration, display.androidChannelId)
    val deliveryId = data["cr_delivery_id"] ?: "comraid-remote"
    val notificationId = deliveryId.hashCode() and Int.MAX_VALUE

    val contentIntent = PendingIntent.getActivity(
      context,
      notificationId,
      ComraidNotificationOpenActivity.contentIntent(context, data),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val builder = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(configuration.smallIconResourceId)
      .setContentTitle(title)
      .setContentText(body)
      .setAutoCancel(true)
      .setContentIntent(contentIntent)
      // Android 8+ takes importance from the channel; this only applies to older devices.
      .setPriority(if (display.highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)

    configuration.accentColor?.let { builder.setColor(it) }
    display.badge?.let { builder.setNumber(it) }
    display.threadId?.let { builder.setGroup(it) }
    // Android 8+ plays the channel's sound; a per-message sound only applies to older devices.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      NotificationChannels.rawSoundUri(context, display.sound)?.let { builder.setSound(it) }
    }

    val image = display.imageUrl?.let { loadImage(it) }
    if (image != null) {
      builder
        .setLargeIcon(image)
        .setStyle(
          NotificationCompat.BigPictureStyle()
            .bigPicture(image)
            .bigLargeIcon(null as Bitmap?)
            .setSummaryText(body),
        )
    } else if (body.isNotBlank()) {
      builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
    }

    PushPayload.actions(data).forEach { action ->
      val actionIntent = PendingIntent.getActivity(
        context,
        (deliveryId + action.id).hashCode() and Int.MAX_VALUE,
        ComraidNotificationOpenActivity.contentIntent(context, data, actionId = action.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      builder.addAction(actionIconResId(context, action.icon), action.text, actionIntent)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    // Same semantics as FCM `android.notification.tag`: a newer push in the thread replaces the older one.
    val manager = NotificationManagerCompat.from(context)
    val tag = display.threadId
    if (tag != null) {
      manager.notify(tag, 0, builder.build())
    } else {
      manager.notify(notificationId, builder.build())
    }
  }

  /** Host drawable named by `content.push.actions[].icon`, or 0 (text-only button) when absent. */
  private fun actionIconResId(context: Context, icon: String?): Int {
    val name = icon?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
    return context.resources.getIdentifier(name, "drawable", context.packageName)
  }

  private fun loadImage(url: String): Bitmap? = runCatching {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return null
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = IMAGE_TIMEOUT_MS
      connection.readTimeout = IMAGE_TIMEOUT_MS
      connection.instanceFollowRedirects = true
      if (connection.responseCode !in 200..299) return null
      val bytes = connection.inputStream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          total += read
          if (total > IMAGE_MAX_BYTES) return null
          out.write(buffer, 0, read)
        }
        out.toByteArray()
      }
      decodeScaled(bytes)
    } finally {
      connection.disconnect()
    }
  }.getOrNull()

  private fun decodeScaled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_MAX_EDGE_PX) {
      sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
  }
}
