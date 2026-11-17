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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComRaidLocalNotificationTest {
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
  fun schedule_persists_and_arms_alarm() {
    val request = ComRaidScheduleRequest(
      notification = ComRaidNotification(id = "local-1", title = "Ping", body = "Soon"),
      triggerAtMillis = nowMillis + 30_000L,
    )

    val result = ComRaid.schedule(request)

    assertEquals("local-1", result.id)
    assertEquals(nowMillis + 30_000L, result.triggerAtMillis)
    assertEquals(1, scheduler.scheduled.size)
    assertEquals(listOf("local-1"), ComRaid.pendingSchedules().map { it.notification.id })
  }

  @Test
  fun cancel_removes_from_store_and_scheduler() {
    ComRaid.schedule(
      ComRaidScheduleRequest(
        notification = ComRaidNotification(id = "local-1", title = "Ping", body = "Soon"),
        triggerAtMillis = nowMillis + 30_000L,
      ),
    )

    ComRaid.cancel("local-1")

    assertTrue(ComRaid.pendingSchedules().isEmpty())
    assertEquals(listOf("local-1"), scheduler.cancelled)
  }

  @Test
  fun pendingSchedules_lists_all_active_requests() {
    ComRaid.schedule(
      ComRaidScheduleRequest(
        notification = ComRaidNotification(id = "a", title = "A", body = "1"),
        triggerAtMillis = nowMillis + 10_000L,
      ),
    )
    ComRaid.schedule(
      ComRaidScheduleRequest(
        notification = ComRaidNotification(id = "b", title = "B", body = "2"),
        triggerAtMillis = nowMillis + 20_000L,
      ),
    )

    assertEquals(setOf("a", "b"), ComRaid.pendingSchedules().map { it.notification.id }.toSet())
  }

  @Test
  fun schedule_past_trigger_throws_invalid_schedule() {
    assertThrows(ComRaidException.InvalidSchedule::class.java) {
      ComRaid.schedule(
        ComRaidScheduleRequest(
          notification = ComRaidNotification(id = "past", title = "Too late", body = "Nope"),
          triggerAtMillis = nowMillis,
        ),
      )
    }
    assertTrue(scheduler.scheduled.isEmpty())
    assertTrue(ComRaid.pendingSchedules().isEmpty())
  }

  @Test
  fun restore_rearms_future_schedules_after_boot() {
    ComRaid.schedule(
      ComRaidScheduleRequest(
        notification = ComRaidNotification(id = "local-1", title = "Ping", body = "Soon"),
        triggerAtMillis = nowMillis + 60_000L,
      ),
    )
    scheduler.scheduled.clear()
    nowMillis += 5_000L

    ComRaid.restoreForTests()

    assertEquals(1, scheduler.scheduled.size)
    assertEquals("local-1", scheduler.scheduled.single().notification.id)
    assertEquals(nowMillis + 55_000L, scheduler.scheduled.single().triggerAtMillis)
  }

  @Test
  fun restore_drops_expired_schedules() {
    ComRaid.schedule(
      ComRaidScheduleRequest(
        notification = ComRaidNotification(id = "local-1", title = "Ping", body = "Soon"),
        triggerAtMillis = nowMillis + 10_000L,
      ),
    )
    scheduler.scheduled.clear()
    nowMillis += 15_000L

    ComRaid.restoreForTests()

    assertTrue(scheduler.scheduled.isEmpty())
    assertTrue(ComRaid.pendingSchedules().isEmpty())
  }

  @Test
  fun restore_rearms_running_timer_when_schedule_row_missing() {
    ComRaid.startTimer(
      notification = ComRaidNotification(id = "focus-1", title = "Focus", body = "Done"),
      durationMillis = 60_000L,
    )
    scheduleStore.remove("focus-1")
    scheduler.scheduled.clear()
    nowMillis += 10_000L

    ComRaid.restoreForTests()

    assertEquals(1, scheduler.scheduled.size)
    assertEquals("focus-1", scheduler.scheduled.single().notification.id)
    assertEquals(nowMillis + 50_000L, scheduler.scheduled.single().triggerAtMillis)
    assertEquals(1, ComRaid.pendingSchedules().size)
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
      error("HTTP should not be called from local notification tests")
  }

  private class FixedTokenProvider(private val token: String) : TokenProvider {
    override fun currentToken(): String = token
  }
}
