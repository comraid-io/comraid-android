package com.comraid.android.internal

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Optional base [FirebaseMessagingService]. Host apps may declare this class (or a subclass)
 * in their manifest so FCM token rotation calls [com.comraid.android.ComRaid.onPushTokenRefresh]
 * and remote messages are displayed / ACK'd.
 *
 * Display and ACK enqueue work from [SdkConfigStore] so they succeed even when the process
 * was started by FCM without a prior foreground [com.comraid.android.ComRaid.configure] call
 * in this process lifetime (configure must have run at least once previously to persist config).
 */
open class ComraidMessagingService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    runCatching { com.comraid.android.ComRaid.onPushTokenRefresh() }
  }

  override fun onMessageReceived(message: RemoteMessage) {
    val data = PushPayload.fromFcmData(
      data = message.data,
      notificationTitle = message.notification?.title,
      notificationBody = message.notification?.body,
    )
    val helpers = BackgroundAckHelpers.from(this) ?: return
    helpers.queue.enqueueFromPayload(AckKind.DELIVERED, data)
    helpers.queue.flush()

    val hasActions = PushPayload.actions(data).isNotEmpty()
    val needsSdkDisplay = hasActions || message.notification == null
    if (needsSdkDisplay && PushPayload.display(data).title.isNotBlank()) {
      NotificationChannels.ensureDefault(this, helpers.configuration)
      RemoteNotificationDisplay.show(this, helpers.configuration, data)
    }
  }
}
