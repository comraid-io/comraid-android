package com.comraid.android.internal

import android.content.Context

/**
 * A Live activity is over on this device (the user swiped it away, or the app ended it). The SDK
 * remembers it so later updates do not bring it back, and tells ComRaid so the server stops sending
 * updates to this device.
 */
internal object LiveUpdateDismissal {
  /** What happened to a report, so WorkManager knows whether to try again. */
  enum class Outcome { REPORTED, FINAL, RETRY }

  /** Stop drawing the activity here. Instant and offline-safe: do this before any network call. */
  fun remember(context: Context, activityId: String) {
    LiveUpdateStore(context).markDismissed(activityId)
  }

  /**
   * Tell ComRaid the activity is over on this device (`dismissed` by the user, or `ended` by the app).
   * One attempt by default: WorkManager retries a [Outcome.RETRY] with backoff. A final refusal (400,
   * 404, 409, 410, 422) or a refused credential (401, 403) is [Outcome.FINAL]: trying again will not help.
   */
  fun report(
    activityId: String,
    subscriptions: SubscriptionStore,
    api: MobileApiClient,
    reason: String = "dismissed",
    retryDelaysMillis: List<Long> = emptyList(),
    sleep: (Long) -> Unit = { Thread.sleep(it) },
  ): Outcome {
    val subscriptionId = subscriptions.subscriptionId?.takeIf { it.isNotBlank() } ?: return Outcome.RETRY
    val credential = subscriptions.credential?.takeIf { it.isNotBlank() } ?: return Outcome.RETRY
    val schedule = listOf<Long?>(null) + retryDelaysMillis
    for (delay in schedule) {
      if (delay != null && delay > 0) sleep(delay)
      val status = runCatching {
        api.reportActivityEnded(subscriptionId, credential, activityId, reason)
      }.getOrNull()
      if (status == 204) return Outcome.REPORTED
      if (status != null && status in FINAL_STATUSES) return Outcome.FINAL
    }
    return Outcome.RETRY
  }

  private val FINAL_STATUSES = setOf(400, 401, 403, 404, 409, 410, 422)
}
