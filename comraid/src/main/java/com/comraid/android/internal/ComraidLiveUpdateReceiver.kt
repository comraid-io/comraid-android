package com.comraid.android.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the delete intent of a Live activity notification (the user swiped it away). */
internal class ComraidLiveUpdateReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val activityId = intent.getStringExtra(EXTRA_ACTIVITY_ID)?.takeIf { it.isNotBlank() } ?: return
    val appContext = context.applicationContext
    // Stop drawing it at once. The report needs the network, so it goes to WorkManager: a broadcast
    // must finish in seconds and cannot wait for retries.
    LiveUpdateDismissal.remember(appContext, activityId)
    runCatching { LiveUpdateReports.schedule(appContext, activityId, "dismissed") }
  }

  companion object {
    const val EXTRA_ACTIVITY_ID = "com.comraid.android.extra.ACTIVITY_ID"
  }
}
