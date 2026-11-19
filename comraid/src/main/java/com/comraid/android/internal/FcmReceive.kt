package com.comraid.android.internal

import android.content.Context

/**
 * What [ComraidMessagingService] does for every FCM message. It takes plain data (no Firebase types)
 * so it can be tested; Firebase is the host app's dependency, not the SDK's.
 */
internal object FcmReceive {
  fun handle(
    context: Context,
    data: Map<String, String>,
    notificationTitle: String?,
    notificationBody: String?,
    hasFcmNotificationBlock: Boolean,
    appInForeground: Boolean,
  ) {
    // A Live activity is drawn as a live update: no host listener event, no Delivery to ACK.
    if (LiveUpdateReceive.handleFcmData(context, data)) return

    val payload = PushPayload.fromFcmData(
      data = data,
      notificationTitle = notificationTitle,
      notificationBody = notificationBody,
    )
    val helpers = BackgroundAckHelpers.from(context) ?: return
    RemoteMessageReceive.handle(
      context = context,
      helpers = helpers,
      data = payload,
      hasFcmNotificationBlock = hasFcmNotificationBlock,
      appInForeground = appInForeground,
    )
  }
}
