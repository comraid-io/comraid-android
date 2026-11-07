package com.comraid.android.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrackerTest {
  private var now = 1_000_000L
  private val tracker = SessionTracker(debounceMs = 30L * 60L * 1000L, clock = { now })

  @Test
  fun cold_start_records_without_background() {
    assertTrue(tracker.shouldRecordSession())
  }

  @Test
  fun repeated_foreground_without_background_skips_until_debounce() {
    assertTrue(tracker.shouldRecordSession())
    tracker.markSessionRecorded()

    now += 5 * 60 * 1000L
    assertFalse(tracker.shouldRecordSession())

    now += 25 * 60 * 1000L + 1
    assertTrue(tracker.shouldRecordSession())
  }

  @Test
  fun background_within_debounce_skips() {
    tracker.markSessionRecorded()
    tracker.onBackground()
    now += 5 * 60 * 1000L
    assertFalse(tracker.shouldRecordSession())
  }

  @Test
  fun background_after_debounce_records_and_consumes_background() {
    tracker.markSessionRecorded()
    tracker.onBackground()
    now += 30 * 60 * 1000L + 1
    assertTrue(tracker.shouldRecordSession())
    tracker.markSessionRecorded()

    // Without a new background, further foregrounds debounce from last session.
    now += 5 * 60 * 1000L
    assertFalse(tracker.shouldRecordSession())
  }
}
