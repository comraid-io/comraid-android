package com.comraid.android.internal

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** Schedules a durable ACK flush that does not wait for the next SDK event. */
internal fun interface AckFlushScheduler {
  fun schedule()
}

internal class WorkManagerAckFlushScheduler(
  context: Context,
) : AckFlushScheduler {
  private val appContext = context.applicationContext

  override fun schedule() {
    val request = OneTimeWorkRequestBuilder<AckFlushWorker>()
      .setConstraints(
        Constraints.Builder()
          .setRequiredNetworkType(NetworkType.CONNECTED)
          .build(),
      )
      .build()
    WorkManager.getInstance(appContext).enqueueUniqueWork(
      UNIQUE_WORK_NAME,
      ExistingWorkPolicy.KEEP,
      request,
    )
  }

  internal companion object {
    const val UNIQUE_WORK_NAME = "comraid-ack-flush"
  }
}
