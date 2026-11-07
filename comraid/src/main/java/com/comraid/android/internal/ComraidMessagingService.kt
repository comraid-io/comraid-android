package com.comraid.android.internal

import com.comraid.android.ComRaid
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Optional base [FirebaseMessagingService]. Host apps may declare this class (or a subclass)
 * in their manifest so FCM token rotation calls [com.comraid.android.ComRaid.onPushTokenRefresh]
 * and remote messages are displayed / ACK'd / forwarded to JS listeners.
 *
 * Display and ACK enqueue work from [SdkConfigStore] so they succeed even when the process
 * was started by FCM without a prior foreground [com.comraid.android.ComRaid.configure] call
 * in this process lifetime (configure must have run at least once previously to persist config).
 * React Native hosts get the same behaviour through this native service (ADR 0014).
 */
open class ComraidMessagingService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    runCatching { ComRaid.onPushTokenRefresh() }
  }

  override fun onMessageReceived(message: RemoteMessage) {
    val data = PushPayload.fromFcmData(
      data = message.data,
      notificationTitle = message.notification?.title,
      notificationBody = message.notification?.body,
    )
    val helpers = BackgroundAckHelpers.from(this) ?: return
    RemoteMessageReceive.handle(
      context = this,
      helpers = helpers,
      data = data,
      hasFcmNotificationBlock = message.notification != null,
      appInForeground = AppForeground.isInForeground(this),
    )
  }
}
