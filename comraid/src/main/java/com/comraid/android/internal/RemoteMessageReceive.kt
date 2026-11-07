package com.comraid.android.internal

import android.content.Context
import com.comraid.android.ComRaid

/**
 * Shared FCM receive path used by [ComraidMessagingService].
 *
 * Runs from a process that may have been started only for FCM (background / swiped closed):
 * config and ACK queue come from [BackgroundAckHelpers], not a foreground [ComRaid] singleton.
 */
internal object RemoteMessageReceive {
  fun handle(
    context: Context,
    helpers: BackgroundAckHelpers,
    data: Map<String, String>,
    hasFcmNotificationBlock: Boolean,
    appInForeground: Boolean,
  ) {
    helpers.queue.enqueueFromPayload(AckKind.DELIVERED, data)
    helpers.queue.flush()

    // Delivered ≠ opened: never auto-ACK opened on receive (ADR 0006).
    ComRaid.notifyMessageReceived(data, appInForeground = appInForeground)

    val shouldDisplay = RemoteDisplayPolicy.shouldAutoDisplay(
      data = data,
      hasFcmNotificationBlock = hasFcmNotificationBlock,
      appInForeground = appInForeground,
      displayForegroundNotifications = helpers.configuration.displayForegroundNotifications,
    )
    if (shouldDisplay) {
      NotificationChannels.ensureDefault(context, helpers.configuration)
      RemoteNotificationDisplay.show(context, helpers.configuration, data)
    }
  }
}
