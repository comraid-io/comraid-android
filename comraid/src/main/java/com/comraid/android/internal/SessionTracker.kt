package com.comraid.android.internal

/**
 * Decides when a foreground should POST `/session`.
 * A new session is recorded on cold start (no prior session this process) or when
 * the app returns to foreground after being backgrounded for at least [debounceMs].
 * If background was never observed, debounce against the last recorded session time
 * so repeated foregrounds do not immediately re-POST.
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
    if (backgroundAt != null) {
      return now - backgroundAt >= debounceMs
    }
    val sessionAt = lastSessionAt ?: return true
    return now - sessionAt >= debounceMs
  }

  fun markSessionRecorded() {
    lastSessionAt = clock()
    // Consume the background signal so later foregrounds debounce from session time
    // until a new background is observed.
    lastBackgroundAt = null
  }

  companion object {
    const val DEFAULT_DEBOUNCE_MS = 30L * 60L * 1000L
  }
}
