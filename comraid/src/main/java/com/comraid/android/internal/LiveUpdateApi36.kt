package com.comraid.android.internal

import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.annotation.RequiresApi

/** The Android 16 (API 36) side of a live update: progress style, promotion and the status chip. */
@RequiresApi(36)
internal object LiveUpdateApi36 {
  /**
   * `Notification.Builder.setRequestPromotedOngoing(true)` stores this extra (the setter itself needs
   * API 36.1), so it is set directly: devices that support promotion read the same key.
   */
  private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

  /** Turn the plain ongoing notification [base] into a live update. */
  fun promote(context: Context, base: Notification, progress: Int?, statusText: String?): Notification {
    val builder = Notification.Builder.recoverBuilder(context, base)
    if (progress != null) {
      builder.setStyle(
        Notification.ProgressStyle()
          .setProgress(progress)
          .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100))),
      )
    }
    builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
    statusText?.let { builder.setShortCriticalText(it) }
    return builder.build()
  }
}
