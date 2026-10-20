package com.comraide.android

data class ComraidConfiguration(
  /** Publishable App ID (`cr_app_…`). Required for mobile API authentication. */
  val appId: String,
  /** API base URL. Defaults to the hosted ComRaid API. */
  val apiBaseUrl: String = DEFAULT_API_BASE_URL,
  val defaultChannelId: String = "comraid.general",
  val defaultChannelName: String = "General",
  val smallIconResourceId: Int,
) {
  companion object {
    const val DEFAULT_API_BASE_URL = "https://api.comraid.io"
  }
}

data class ComraidNotification(
  val id: String,
  val title: String,
  val body: String,
  val channelId: String? = null,
  val data: Map<String, String> = emptyMap(),
)

enum class SchedulePrecision { INEXACT, EXACT }

enum class FallbackPolicy { FAIL, USE_INEXACT }

data class ComraidScheduleRequest(
  val notification: ComraidNotification,
  val triggerAtMillis: Long,
  val precision: SchedulePrecision = SchedulePrecision.INEXACT,
  val fallbackPolicy: FallbackPolicy = FallbackPolicy.FAIL,
)

data class ComraidScheduleResult(
  val id: String,
  val requestedPrecision: SchedulePrecision,
  val appliedPrecision: SchedulePrecision,
  val triggerAtMillis: Long,
  val fallbackReason: String? = null,
)

data class ComraidCapabilities(
  val notificationsEnabled: Boolean,
  val exactAlarmsAvailable: Boolean,
)

sealed class ComraidException(message: String) : IllegalStateException(message) {
  class NotConfigured : ComraidException("Comraid.configure must be called first")
  class NotReady : ComraidException("Subscription is not ready; grant notification permission and register first")
  class InvalidSchedule(message: String) : ComraidException(message)
  class ExactAlarmUnavailable : ComraidException("Exact alarm access is unavailable")
  class ApiError(message: String) : ComraidException(message)
}
