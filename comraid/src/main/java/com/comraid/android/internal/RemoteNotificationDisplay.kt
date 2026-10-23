package com.comraid.android.internal

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.comraid.android.ComRaidConfiguration

internal object RemoteNotificationDisplay {
  fun show(
    context: Context,
    configuration: ComRaidConfiguration,
    data: Map<String, String>,
  ) {
    val display = PushPayload.display(data)
    val title = display.title.ifBlank { return }
    val body = display.body
    val channelId = display.androidChannelId ?: configuration.defaultChannelId
    val deliveryId = data["cr_delivery_id"] ?: "comraid-remote"
    val notificationId = deliveryId.hashCode() and Int.MAX_VALUE

    val contentOpenUri = PushPayload.contentOpenUri(deliveryId, data)
    val contentIntent = PendingIntent.getBroadcast(
      context,
      notificationId,
      Intent(context, ComraidNotificationActionReceiver::class.java)
        .setData(Uri.parse(contentOpenUri))
        .putExtra(ComraidNotificationActionReceiver.EXTRA_KIND, "opened")
        .putExtra(ComraidNotificationActionReceiver.EXTRA_DATA_JSON, PushPayload.toJsonObject(data).toString()),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val builder = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(configuration.smallIconResourceId)
      .setContentTitle(title)
      .setContentText(body)
      .setAutoCancel(true)
      .setContentIntent(contentIntent)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)

    PushPayload.actions(data).forEach { action ->
      val actionIntent = PendingIntent.getBroadcast(
        context,
        (deliveryId + action.id).hashCode() and Int.MAX_VALUE,
        Intent(context, ComraidNotificationActionReceiver::class.java)
          .setData(Uri.parse("comraid://action/$deliveryId/${action.id}"))
          .putExtra(ComraidNotificationActionReceiver.EXTRA_KIND, "opened")
          .putExtra(ComraidNotificationActionReceiver.EXTRA_ACTION_ID, action.id)
          .putExtra(ComraidNotificationActionReceiver.EXTRA_DATA_JSON, PushPayload.toJsonObject(data).toString()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      builder.addAction(0, action.text, actionIntent)
    }

    NotificationManagerCompat.from(context).notify(notificationId, builder.build())
  }
}
