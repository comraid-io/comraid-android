package com.comraid.android.internal

/**
 * Decides whether [ComraidMessagingService] should post a system tray item for a remote push.
 *
 * - Data-only Notifications never auto-draw (ADR 0006); apps use [com.comraid.android.ComRaid.presentNotification].
 * - Non–data-only alerts show in the foreground by default; hosts may opt out via configuration.
 * - Background FCM notification-block messages are drawn by the OS; the SDK must not double-draw.
 * - Non–data-only actions payloads still use SDK tray presentation (hybrid path).
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

    val hasActions = PushPayload.actions(data).isNotEmpty()
    if (hasActions) return true

    if (appInForeground && displayForegroundNotifications) return true

    // Background: OS owns notification-block presentation.
    if (hasFcmNotificationBlock) return false

    return false
  }
}
