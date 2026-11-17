package com.comraid.android.internal

/**
 * Decides how a notification tap brings the host to the foreground.
 * Deep links are delivered on the opened event / [com.comraid.android.ComRaid.getInitialNotification];
 * the SDK never fires [android.content.Intent.ACTION_VIEW].
 */
internal object HostOpenLaunch {
  sealed class Target {
    data class PackageLauncher(val extras: Map<String, String>) : Target()
  }

  fun resolve(data: Map<String, String>): Target =
    Target.PackageLauncher(extras = PushPayload.openIntentExtras(data))
}
