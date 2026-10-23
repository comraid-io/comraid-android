package com.comraid.android

data class ComRaidConfiguration(
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

data class ComRaidCapabilities(
  val notificationsEnabled: Boolean,
  val exactAlarmsAvailable: Boolean,
)

/**
 * Subscription (device) Properties sent on register / token refresh
 * (`app_version` + `device` object per mobile OpenAPI).
 */
data class DeviceProperties(
  val appVersion: String? = null,
  val model: String? = null,
  val osVersion: String? = null,
  val extras: Map<String, Any?> = emptyMap(),
)

sealed class ComRaidException(message: String) : IllegalStateException(message) {
  class NotConfigured : ComRaidException("ComRaid.configure must be called first")
  class NotReady : ComRaidException("Subscription is not ready; grant notification permission and register first")
  class InvalidSchedule(message: String) : ComRaidException(message)
  class ExactAlarmUnavailable : ComRaidException("Exact alarm access is unavailable")
  class ApiError(message: String) : ComRaidException(message)
}
