package com.comraid.android

data class ComRaidConfiguration(
  /** Publishable App ID (`cr_app_…`). Required for mobile API authentication. */
  val appId: String,
  /** API base URL. Defaults to the hosted ComRaid API. */
  val apiBaseUrl: String = DEFAULT_API_BASE_URL,
  val defaultChannelId: String = "comraid.general",
  val defaultChannelName: String = "General",
  val smallIconResourceId: Int,
  /**
   * When true (default), non–data-only Notifications are shown by the SDK while the app is
   * in the foreground. Set false to handle alerts only via receive listeners / `onMessage`.
   */
  val displayForegroundNotifications: Boolean = true,
  /** Optional ARGB accent colour for SDK-drawn notifications (small icon and action tint). */
  val accentColor: Int? = null,
) {
  companion object {
    const val DEFAULT_API_BASE_URL = "https://api.comraid.io"
  }
}

data class ComRaidNotification(
  val id: String,
  val title: String,
  val body: String,
  val channelId: String? = null,
  val data: Map<String, String> = emptyMap(),
)

enum class SchedulePrecision { INEXACT, EXACT }

enum class FallbackPolicy { FAIL, USE_INEXACT }

data class ComRaidScheduleRequest(
  val notification: ComRaidNotification,
  val triggerAtMillis: Long,
  val precision: SchedulePrecision = SchedulePrecision.INEXACT,
  val fallbackPolicy: FallbackPolicy = FallbackPolicy.FAIL,
)

data class ComRaidScheduleResult(
  val id: String,
  val requestedPrecision: SchedulePrecision,
  val appliedPrecision: SchedulePrecision,
  val triggerAtMillis: Long,
  val fallbackReason: String? = null,
)

enum class TimerState { RUNNING, PAUSED, CANCELLED, COMPLETED }

/**
 * Local countdown timer (device Local notification). Not a Message / Delivery.
 * Survives reboot via durable store; [RUNNING] timers are re-armed on boot.
 */
data class ComRaidTimer(
  val id: String,
  val notification: ComRaidNotification,
  val state: TimerState,
  val targetAtMillis: Long?,
  val remainingDurationMillis: Long,
  val precision: SchedulePrecision = SchedulePrecision.INEXACT,
  val fallbackPolicy: FallbackPolicy = FallbackPolicy.FAIL,
)

data class ComRaidCapabilities(
  val notificationsEnabled: Boolean,
  val exactAlarmsAvailable: Boolean,
)

/**
 * Subscription / Contact Properties sent on register, token refresh, and session.
 * The SDK fills these automatically; [ComRaid.setDeviceProperties] overrides
 * individual fields (non-null wins) for the next outbound call.
 */
data class DeviceProperties(
  val appVersion: String? = null,
  val sdkVersion: String? = null,
  val model: String? = null,
  val os: String? = null,
  val osVersion: String? = null,
  val locale: String? = null,
  val timezone: String? = null,
  val language: String? = null,
  val country: String? = null,
  val extras: Map<String, Any?> = emptyMap(),
)

sealed class ComRaidException(message: String) : IllegalStateException(message) {
  class NotConfigured : ComRaidException("ComRaid.configure must be called first")
  class NotReady : ComRaidException("Subscription is not ready; grant notification permission and register first")
  class InvalidSchedule(message: String) : ComRaidException(message)
  class ExactAlarmUnavailable : ComRaidException("Exact alarm access is unavailable")
  class TimerNotFound : ComRaidException("Timer not found")
  class TimerNotPaused : ComRaidException("Timer is not in the expected pause/resume state")
  class ApiError(message: String) : ComRaidException(message)
}
