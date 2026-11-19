package com.comraid.android.internal

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.comraid.android.ComRaidConfiguration
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Seam: the user swipes a Live activity away. The SDK stops drawing it on this device and tells
 * ComRaid so the server stops sending to it (`POST .../activities/{id}/ended`, reason `dismissed`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LiveUpdateDismissalTest {
  private lateinit var context: Context
  private lateinit var store: LiveUpdateStore
  private lateinit var subscriptions: InMemorySubscriptionStore
  private lateinit var transport: ScriptedTransport
  private val configuration = ComRaidConfiguration(
    appId = "cr_app_test",
    apiBaseUrl = "https://api.example.com",
    smallIconResourceId = android.R.drawable.sym_def_app_icon,
  )

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    store = LiveUpdateStore(context)
    store.clearForTests()
    subscriptions = InMemorySubscriptionStore().apply { save("sub-1", "cred-1") }
    transport = ScriptedTransport()
    SdkConfigStore(context).save(configuration)
  }

  private fun dismiss(delays: List<Long> = emptyList()): LiveUpdateDismissal.Outcome {
    LiveUpdateDismissal.remember(context, "order_8392")
    return LiveUpdateDismissal.report(
      activityId = "order_8392",
      subscriptions = subscriptions,
      api = MobileApiClient(configuration.appId, transport),
      retryDelaysMillis = delays,
      sleep = {},
    )
  }

  @Test
  fun aDismissalIsRememberedAndReported() {
    assertEquals(LiveUpdateDismissal.Outcome.REPORTED, dismiss())

    assertTrue(store.isDismissed("order_8392"))
    val request = transport.requests.single()
    assertEquals("POST", request.method)
    assertEquals("/mobile/subscriptions/sub-1/activities/order_8392/ended", request.path)
    assertEquals("cr_app_test", request.headers["X-ComRaid-App-Id"])
    val body = JSONObject(request.body!!)
    assertEquals("cred-1", body.getString("subscription_credential"))
    assertEquals("dismissed", body.getString("reason"))
  }

  @Test
  fun withoutASubscriptionItIsStillRememberedLocallyButNothingIsSent() {
    subscriptions.clear()
    // Nothing to report under: try again later, once the SDK has registered.
    assertEquals(LiveUpdateDismissal.Outcome.RETRY, dismiss())
    assertTrue(store.isDismissed("order_8392"))
    assertTrue(transport.requests.isEmpty())
  }

  @Test
  fun aNetworkOrServerErrorIsRetried() {
    transport.script(HttpResponse(503, "{}"), HttpResponse(503, "{}"))
    assertEquals(LiveUpdateDismissal.Outcome.REPORTED, dismiss(delays = listOf(0L, 0L))) // then 204
    assertEquals(3, transport.requests.size)
  }

  @Test
  fun aFinalRefusalIsNotRetried() {
    transport.script(HttpResponse(409, """{"error":{"code":"activity_not_active","message":"x"}}"""))
    assertEquals(LiveUpdateDismissal.Outcome.FINAL, dismiss(delays = listOf(0L, 0L)))
    assertEquals(1, transport.requests.size)
    assertTrue("still suppressed locally", store.isDismissed("order_8392"))
  }

  @Test
  fun aRefusedCredentialIsNotRetriedInARush() {
    transport.script(HttpResponse(401, "{}"))
    assertEquals(LiveUpdateDismissal.Outcome.FINAL, dismiss(delays = listOf(0L, 0L)))
    assertEquals(1, transport.requests.size)
  }

  @Test
  fun theOngoingNotificationReportsItsDismissal() {
    SdkConfigStore(context).save(configuration)
    LiveUpdateReceive.handleFcmData(
      context,
      mapOf("cr_activity_id" to "order_8392", "cr_activity_type" to "delivery", "cr_activity_op" to "start", "cr_activity_title" to "On the way"),
    )

    val n = shadowOf(context.getSystemService(NotificationManager::class.java))
      .getNotification("order_8392", LiveUpdateNotifier.NOTIFICATION_ID)!!
    val delete = n.deleteIntent
    assertNotNull("swiping the ongoing notification away must be reported", delete)
    val intent = shadowOf(delete).savedIntent
    assertEquals(ComraidLiveUpdateReceiver::class.java.name, intent.component?.className)
    assertEquals("order_8392", intent.getStringExtra(ComraidLiveUpdateReceiver.EXTRA_ACTIVITY_ID))
  }

  @Test
  fun aServerErrorWithNoRetriesLeftIsLeftForWorkManagerToRetry() {
    transport.script(HttpResponse(503, "{}"))
    assertEquals(LiveUpdateDismissal.Outcome.RETRY, dismiss())
  }

  @Test
  fun theReceiverRecordsTheDismissalAndSchedulesTheReportInsteadOfDoingNetworkWork() {
    val scheduled = mutableListOf<Pair<String, String>>()
    LiveUpdateReports.schedulerOverride = { _, id, reason -> scheduled += id to reason }
    try {
      val intent = Intent(context, ComraidLiveUpdateReceiver::class.java)
        .putExtra(ComraidLiveUpdateReceiver.EXTRA_ACTIVITY_ID, "order_8392")
      ComraidLiveUpdateReceiver().onReceive(context, intent)

      assertTrue(store.isDismissed("order_8392"))
      assertEquals(listOf("order_8392" to "dismissed"), scheduled)
      assertTrue("the broadcast must not make HTTP calls itself", transport.requests.isEmpty())
    } finally {
      LiveUpdateReports.schedulerOverride = null
    }
  }

  @Test
  fun twoActivitiesWhoseIdsCollideStillDismissThemselves() {
    // "Aa" and "BB" have the same hashCode: their dismiss intents must still differ.
    assertEquals("Aa".hashCode(), "BB".hashCode())
    for (id in listOf("Aa", "BB")) {
      LiveUpdateReceive.handleFcmData(
        context,
        mapOf("cr_activity_id" to id, "cr_activity_type" to "t", "cr_activity_op" to "start", "cr_activity_title" to "T"),
      )
    }
    val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
    for (id in listOf("Aa", "BB")) {
      val delete = manager.getNotification(id, LiveUpdateNotifier.NOTIFICATION_ID)!!.deleteIntent
      assertEquals(id, shadowOf(delete).savedIntent.getStringExtra(ComraidLiveUpdateReceiver.EXTRA_ACTIVITY_ID))
    }
  }

  @Test
  fun aFinalStateNotificationDoesNotReportAnything() {
    LiveUpdateReceive.handleFcmData(
      context,
      mapOf("cr_activity_id" to "order_8392", "cr_activity_type" to "delivery", "cr_activity_op" to "end", "cr_activity_title" to "Delivered"),
    )
    val n = shadowOf(context.getSystemService(NotificationManager::class.java))
      .getNotification("order_8392", LiveUpdateNotifier.NOTIFICATION_ID)!!
    assertFalse("swiping a finished activity away is not a dismissal worth reporting", n.deleteIntent != null)
  }

  private class ScriptedTransport : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    private val scripted = ArrayDeque<HttpResponse>()
    fun script(vararg responses: HttpResponse) { scripted.addAll(responses) }
    override fun execute(request: HttpRequest): HttpResponse {
      requests += request
      return scripted.removeFirstOrNull() ?: HttpResponse(204, "")
    }
  }
}
