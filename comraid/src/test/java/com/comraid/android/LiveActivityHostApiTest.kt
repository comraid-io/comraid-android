package com.comraid.android

import android.app.NotificationManager
import android.content.Context
import com.comraid.android.internal.HttpRequest
import com.comraid.android.internal.HttpResponse
import com.comraid.android.internal.HttpTransport
import com.comraid.android.internal.InMemorySubscriptionStore
import com.comraid.android.internal.LiveUpdateReceive
import com.comraid.android.internal.LiveUpdateReports
import com.comraid.android.internal.LiveUpdateStore
import com.comraid.android.internal.SdkConfigStore
import com.comraid.android.internal.TokenProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Seam: the public Live activity methods the app (and the React Native module) call to see which
 * live updates are showing and to end one from the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LiveActivityHostApiTest {
  private lateinit var context: Context
  private lateinit var manager: NotificationManager
  private lateinit var transport: RecordingTransport
  private val reports = CopyOnWriteArrayList<Pair<String, String>>()

  private val configuration = ComRaidConfiguration(
    appId = "cr_app_test",
    apiBaseUrl = "https://api.example.com",
    smallIconResourceId = android.R.drawable.sym_def_app_icon,
  )

  @org.junit.After
  fun tearDown() {
    LiveUpdateReports.schedulerOverride = null
  }

  @Before
  fun setUp() {
    ComRaid.resetForTests()
    context = RuntimeEnvironment.getApplication()
    manager = context.getSystemService(NotificationManager::class.java)
    LiveUpdateStore(context).clearForTests()
    SdkConfigStore(context).save(configuration)
    transport = RecordingTransport()
    reports.clear()
    LiveUpdateReports.schedulerOverride = { _, id, reason -> reports += id to reason }
    ComRaid.configureForTests(
      configuration = configuration,
      transport = transport,
      tokenProvider = TokenProvider { "fcm-token" },
      subscriptionStore = InMemorySubscriptionStore().apply { save("sub-1", "cred-1") },
      context = context,
    )
  }

  private fun live(op: String, id: String, type: String = "delivery", title: String? = "On the way") =
    LiveUpdateReceive.handleFcmData(
      context,
      buildMap {
        put("cr_activity_id", id)
        put("cr_activity_type", type)
        put("cr_activity_op", op)
        title?.let { put("cr_activity_title", it) }
      },
    )

  @Test
  fun listsTheLiveActivitiesThatAreShowing() {
    live("start", "order_2")
    live("start", "ride_1", type = "ride")

    assertEquals(
      listOf(ComRaidLiveActivity("order_2", "delivery"), ComRaidLiveActivity("ride_1", "ride")),
      ComRaid.liveActivities().sortedBy { it.id },
    )
  }

  @Test
  fun aFinishedActivityAndOrdinaryNotificationsAreNotListed() {
    live("start", "order_1")
    live("end", "order_1", title = "Delivered") // leaves a final-state notification, not a running one
    manager.notify(42, androidx.core.app.NotificationCompat.Builder(context, "x")
      .setSmallIcon(android.R.drawable.sym_def_app_icon).setContentTitle("hi").build())

    assertTrue(ComRaid.liveActivities().isEmpty())
  }

  @Test
  fun endingOneRemovesItKeepsItOffTheDeviceAndTellsComRaid() {
    live("start", "order_1")
    live("start", "order_2")

    assertTrue(ComRaid.endLiveActivity("order_1"))

    assertEquals(listOf("order_2"), ComRaid.liveActivities().map { it.id })
    live("update", "order_1", title = "Still going")
    assertEquals("an update after the app ended it must not bring it back", 1, shadowOf(manager).allNotifications.size)

    // The report is handed to WorkManager (retried until it gets through), not sent from this call.
    assertEquals(listOf("order_1" to "ended"), reports.toList())
  }

  @Test
  fun endingAnActivityThatIsNotShowingDoesNothing() {
    assertFalse(ComRaid.endLiveActivity("nope"))
    assertTrue(reports.isEmpty())
  }

  @Test
  fun withoutConfigureThereIsNothingToListOrEnd() {
    ComRaid.resetForTests()
    assertTrue(ComRaid.liveActivities().isEmpty())
    assertFalse(ComRaid.endLiveActivity("order_1"))
  }

  private class RecordingTransport : HttpTransport {
    val requests = CopyOnWriteArrayList<HttpRequest>()
    override fun execute(request: HttpRequest): HttpResponse {
      requests += request
      return HttpResponse(204, "")
    }
  }
}
