package com.comraid.android.internal

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** WorkManager entry point for [LiveUpdateReports]. */
internal class LiveUpdateReportWorker(
  context: Context,
  params: WorkerParameters,
) : Worker(context, params) {
  override fun doWork(): Result {
    val activityId = inputData.getString(KEY_ACTIVITY_ID) ?: return Result.success()
    val reason = inputData.getString(KEY_REASON) ?: return Result.success()
    // The device already stopped drawing it; a report that never gets through only costs wasted pushes.
    if (runAttemptCount >= MAX_ATTEMPTS) return Result.success()
    return when (LiveUpdateReports.execute(applicationContext, activityId, reason)) {
      LiveUpdateDismissal.Outcome.RETRY -> Result.retry()
      LiveUpdateDismissal.Outcome.REPORTED, LiveUpdateDismissal.Outcome.FINAL -> Result.success()
    }
  }

  companion object {
    const val KEY_ACTIVITY_ID = "activity_id"
    const val KEY_REASON = "reason"
    private const val MAX_ATTEMPTS = 8
  }
}
