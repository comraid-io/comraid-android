package com.comraid.android

import com.comraid.android.internal.HttpRequest
import com.comraid.android.internal.HttpResponse
import com.comraid.android.internal.HttpTransport
import com.comraid.android.internal.InMemoryJsonBlobStore
import com.comraid.android.internal.InMemorySubscriptionStore
import com.comraid.android.internal.NotificationScheduler
import com.comraid.android.internal.ScheduleStore
import com.comraid.android.internal.TimerStore
import com.comraid.android.internal.TokenProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComRaidTimerTest {
  private lateinit var scheduleStore: ScheduleStore
  private lateinit var timerStore: TimerStore
  private lateinit var scheduler: RecordingScheduler
  private var nowMillis = 1_000_000L

  @Before
  fun setUp() {
    ComRaid.resetForTests()
    scheduleStore = ScheduleStore(InMemoryJsonBlobStore())
    timerStore = TimerStore(InMemoryJsonBlobStore())
    scheduler = RecordingScheduler()
    ComRaid.configureForTests(
      configuration = ComRaidConfiguration(
        appId = "cr_app_test",
        smallIconResourceId = 1,
      ),
      transport = UnusedTransport(),
      tokenProvider = FixedTokenProvider("token"),
      subscriptionStore = InMemorySubscriptionStore(),
      scheduleStore = scheduleStore,
      timerStore = timerStore,
      scheduler = scheduler,
      clock = { nowMillis },
    )
  }

  @Test
  fun startTimer_persists_running_timer_and_schedules() {
    val result = ComRaid.startTimer(
      notification = ComRaidNotification(id = "focus-1", title = "Focus", body = "Done"),
      durationMillis = 60_000L,
    )

    assertEquals("focus-1", result.id)
    assertEquals(nowMillis + 60_000L, result.triggerAtMillis)
    assertEquals(1, scheduler.scheduled.size)
    val timer = ComRaid.timers().single()
    assertEquals("focus-1", timer.id)
    assertEquals(TimerState.RUNNING, timer.state)
    assertEquals(nowMillis + 60_000L, timer.targetAtMillis)
    assertEquals(60_000L, timer.remainingDurationMillis)
    assertEquals(1, scheduleStore.all().size)
  }

  @Test
  fun pauseTimer_clears_schedule_and_stores_remaining() {
    ComRaid.startTimer(
      notification = ComRaidNotification(id = "focus-1", title = "Focus", body = "Done"),
      durationMillis = 60_000L,
    )
    nowMillis += 15_000L

    val paused = ComRaid.pauseTimer("focus-1")

    assertEquals(TimerState.PAUSED, paused.state)
    assertNull(paused.targetAtMillis)
    assertEquals(45_000L, paused.remainingDurationMillis)
    assertTrue(scheduleStore.all().isEmpty())
    assertEquals(listOf("focus-1"), scheduler.cancelled)
  }

  @Test
  fun resumeTimer_reschedules_from_remaining() {
    ComRaid.startTimer(
      notification = ComRaidNotification(id = "focus-1", title = "Focus", body = "Done"),
      durationMillis = 60_000L,
    )
    nowMillis += 20_000L
    ComRaid.pauseTimer("focus-1")
    scheduler.scheduled.clear()
    scheduler.cancelled.clear()
    nowMillis += 5_000L

    val resumed = ComRaid.resumeTimer("focus-1")

    assertEquals(TimerState.RUNNING, resumed.state)
    assertEquals(nowMillis + 40_000L, resumed.targetAtMillis)
    assertEquals(40_000L, resumed.remainingDurationMillis)
    assertEquals(1, scheduler.scheduled.size)
    assertEquals(nowMillis + 40_000L, scheduler.scheduled.single().triggerAtMillis)
  }

  @Test
  fun cancelTimer_marks_cancelled_and_removes_schedule() {
    ComRaid.startTimer(
      notification = ComRaidNotification(id = "focus-1", title = "Focus", body = "Done"),
      durationMillis = 60_000L,
    )

    ComRaid.cancelTimer("focus-1")

    val timer = ComRaid.timers().single()
    assertEquals(TimerState.CANCELLED, timer.state)
    assertNull(timer.targetAtMillis)
    assertTrue(scheduleStore.all().isEmpty())
  }

  @Test
  fun pauseTimer_unknown_id_throws_timerNotFound() {
    assertThrows(ComRaidException.TimerNotFound::class.java) {
      ComRaid.pauseTimer("missing")
    }
  }

  @Test
  fun pauseTimer_when_paused_throws_timerNotPaused() {
    ComRaid.startTimer(
      notification = ComRaidNotification(id = "focus-1", title = "Focus", body = "Done"),
      durationMillis = 60_000L,
    )
    ComRaid.pauseTimer("focus-1")
    assertThrows(ComRaidException.TimerNotPaused::class.java) {
      ComRaid.pauseTimer("focus-1")
    }
  }

  @Test
  fun restore_reschedules_running_timers_after_boot() {
    ComRaid.startTimer(
      notification = ComRaidNotification(id = "focus-1", title = "Focus", body = "Done"),
      durationMillis = 60_000L,
    )
    scheduler.scheduled.clear()
    // Simulate reboot wiping AlarmManager while durable stores survive.
    nowMillis += 10_000L

    ComRaid.restoreForTests()

    assertEquals(1, scheduler.scheduled.size)
    assertEquals("focus-1", scheduler.scheduled.single().notification.id)
    assertEquals(nowMillis + 50_000L, scheduler.scheduled.single().triggerAtMillis)
  }

  @Test
  fun restore_does_not_reschedule_paused_timers() {
    ComRaid.startTimer(
      notification = ComRaidNotification(id = "focus-1", title = "Focus", body = "Done"),
      durationMillis = 60_000L,
    )
    ComRaid.pauseTimer("focus-1")
    scheduler.scheduled.clear()

    ComRaid.restoreForTests()

    assertTrue(scheduler.scheduled.isEmpty())
    assertEquals(TimerState.PAUSED, ComRaid.timers().single().state)
  }

  private class RecordingScheduler : NotificationScheduler {
    val scheduled = mutableListOf<ComRaidScheduleRequest>()
    val cancelled = mutableListOf<String>()

    override fun schedule(
      request: ComRaidScheduleRequest,
      configuration: ComRaidConfiguration,
    ): ComRaidScheduleResult {
      scheduled += request
      return ComRaidScheduleResult(
        id = request.notification.id,
        requestedPrecision = request.precision,
        appliedPrecision = request.precision,
        triggerAtMillis = request.triggerAtMillis,
      )
    }

    override fun cancel(id: String) {
      cancelled += id
    }
  }

  private class UnusedTransport : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse =
      error("HTTP should not be called from timer tests")
  }

  private class FixedTokenProvider(private val token: String) : TokenProvider {
    override fun currentToken(): String = token
  }
}
