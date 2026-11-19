package com.comraid.android.internal

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Gets the "this activity is over on this device" report to ComRaid. It runs in WorkManager, so it is
 * retried with backoff until it gets through and never holds a broadcast open.
 */
internal object LiveUpdateReports {
  /** Test seam: replaces WorkManager scheduling. */
  @Volatile var schedulerOverride: ((Context, String, String) -> Unit)? = null

  fun schedule(context: Context, activityId: String, reason: String) {
    schedulerOverride?.let { return it(context, activityId, reason) }
    val request = OneTimeWorkRequestBuilder<LiveUpdateReportWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .setInputData(workDataOf(LiveUpdateReportWorker.KEY_ACTIVITY_ID to activityId, LiveUpdateReportWorker.KEY_REASON to reason))
      .build()
    WorkManager.getInstance(context.applicationContext)
      .enqueueUniqueWork("comraid-live-update-$reason-$activityId", ExistingWorkPolicy.REPLACE, request)
  }

  /** What the worker runs: build the production client from the persisted config and report once. */
  fun execute(context: Context, activityId: String, reason: String): LiveUpdateDismissal.Outcome {
    val appContext = context.applicationContext
    val configuration = SdkConfigStore(appContext).load() ?: return LiveUpdateDismissal.Outcome.FINAL
    return LiveUpdateDismissal.report(
      activityId = activityId,
      subscriptions = SharedPreferencesSubscriptionStore(appContext),
      api = MobileApiClient(configuration.appId.trim(), UrlConnectionHttpTransport(configuration.apiBaseUrl)),
      reason = reason,
    )
  }
}
