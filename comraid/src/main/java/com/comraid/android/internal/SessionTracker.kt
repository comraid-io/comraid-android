package com.comraid.android.internal

/**
 * Decides when a foreground should POST `/session`.
 * A new session is recorded on cold start (no prior session this process) or when
 * the app returns to foreground after being backgrounded for at least [debounceMs].
 */
internal class SessionTracker(
  private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
  private val clock: () -> Long,
) {
  private var lastBackgroundAt: Long? = null
  private var lastSessionAt: Long? = null

  fun onBackground() {
    lastBackgroundAt = clock()
  }

  fun shouldRecordSession(): Boolean {
    val now = clock()
    val backgroundAt = lastBackgroundAt
    return when {
      backgroundAt != null -> now - backgroundAt >= debounceMs
      lastSessionAt == null -> true
      else -> false
    }
  }

  fun markSessionRecorded() {
    lastSessionAt = clock()
  }

  companion object {
    const val DEFAULT_DEBOUNCE_MS = 30L * 60L * 1000L
  }
}
