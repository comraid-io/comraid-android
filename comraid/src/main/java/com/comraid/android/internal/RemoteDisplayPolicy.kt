package com.comraid.android.internal

/**
 * Decides whether [ComraidMessagingService] should post a system tray item for a remote push.
 *
 * - Data-only Notifications never auto-draw (ADR 0006); apps use [com.comraid.android.ComRaid.presentNotification].
 * - Non–data-only alerts are presented by the SDK (ADR 0014): FCM sends data messages only.
 * - Non–data-only alerts show in the foreground by default; hosts may opt out via configuration.
 * - Background FCM notification-block messages (legacy / non-ComRaid) are drawn by the OS; do not double-draw.
 */
internal object RemoteDisplayPolicy {
  fun shouldAutoDisplay(
    data: Map<String, String>,
    hasFcmNotificationBlock: Boolean,
    appInForeground: Boolean,
    displayForegroundNotifications: Boolean,
  ): Boolean {
    if (PushPayload.isDataOnly(data)) return false
    if (PushPayload.display(data).title.isBlank()) return false

    if (appInForeground && !displayForegroundNotifications) return false

    // Legacy / non-ComRaid: OS owns notification-block presentation in the background.
    if (!appInForeground && hasFcmNotificationBlock) return false

    return true
  }
}
