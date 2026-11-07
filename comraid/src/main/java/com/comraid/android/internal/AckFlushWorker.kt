package com.comraid.android.internal

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** WorkManager entry point: flushes the durable ACK queue when the network is available. */
internal class AckFlushWorker(
  context: Context,
  params: WorkerParameters,
) : Worker(context, params) {
  override fun doWork(): Result {
    val helpers = BackgroundAckHelpers.from(applicationContext) ?: return Result.success()
    return try {
      helpers.queue.flush()
      Result.success()
    } catch (_: Exception) {
      Result.retry()
    }
  }
}
