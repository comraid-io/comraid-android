package com.comraid.android.internal

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.comraid.android.ComRaidConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Seam: a Live activity FCM data message arriving at the SDK. [LiveUpdateReceive.handleFcmData] is what
 * the messaging service calls first; the tests read the notifications it posts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LiveUpdateReceiveTest {
  private lateinit var context: Context
  private lateinit var manager: NotificationManager

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    manager = context.getSystemService(NotificationManager::class.java)
    LiveUpdateStore(context).clearForTests()
    SdkConfigStore(context).save(
      ComRaidConfiguration(
        appId = "cr_app_test",
        apiBaseUrl = "https://api.example.com",
        smallIconResourceId = android.R.drawable.sym_def_app_icon,
      ),
    )
  }

  private fun message(
    op: String,
    id: String = "order_8392",
    title: String? = "On the way",
    body: String? = "7 min",
    progress: String? = "70",
    status: String? = null,
  ): Map<String, String> = buildMap {
    put("cr_activity_id", id)
    put("cr_activity_type", "delivery")
    put("cr_activity_op", op)
    title?.let { put("cr_activity_title", it) }
    body?.let { put("cr_activity_body", it) }
    progress?.let { put("cr_activity_progress", it) }
    status?.let { put("cr_activity_status_text", it) }
    // What the server also sends, so SDKs without live update support ignore the message.
    put("cr_data_only", "true")
    put("cr_title", title ?: "")
    put("cr_body", body ?: "")
  }

  private fun posted(id: String = "order_8392"): Notification? =
    shadowOf(manager).getNotification(id, LiveUpdateNotifier.NOTIFICATION_ID)

  private fun Notification.text(key: String): String? = extras.getCharSequence(key)?.toString()

  @Test
  fun anOrdinaryMessageIsNotHandled() {
    val handled = LiveUpdateReceive.handleFcmData(context, mapOf("cr_title" to "Hi", "cr_body" to "There"))
    assertFalse(handled)
    assertTrue(shadowOf(manager).allNotifications.isEmpty())
  }

  @Test
  fun startDrawsAnOngoingNotificationWithProgress() {
    assertTrue(LiveUpdateReceive.handleFcmData(context, message("start", progress = "10")))

    val n = posted()
    assertNotNull("the live update must be posted under the activity id", n)
    n!!
    assertEquals("On the way", n.text(Notification.EXTRA_TITLE))
    assertEquals("7 min", n.text(Notification.EXTRA_TEXT))
    assertTrue("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    assertTrue("must not ring on every update", n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    assertEquals(100, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    assertEquals(10, n.extras.getInt(Notification.EXTRA_PROGRESS))
    assertEquals(LiveUpdateNotifier.CHANNEL_ID, n.channelId)
  }

  @Test
  fun anUpdateReplacesTheNotificationInPlace() {
    LiveUpdateReceive.handleFcmData(context, message("start", progress = "10"))
    LiveUpdateReceive.handleFcmData(context, message("update", title = "Almost there", body = "2 min", progress = "90"))

    assertEquals(1, shadowOf(manager).allNotifications.size)
    val n = posted()!!
    assertEquals("Almost there", n.text(Notification.EXTRA_TITLE))
    assertEquals(90, n.extras.getInt(Notification.EXTRA_PROGRESS))
  }

  @Test
  fun noProgressMeansNoProgressBar() {
    LiveUpdateReceive.handleFcmData(context, message("start", progress = null))
    assertEquals(0, posted()!!.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
  }

  @Test
  fun statusTextIsShown() {
    LiveUpdateReceive.handleFcmData(context, message("update", status = "Arriving soon"))
    assertEquals("Arriving soon", posted()!!.text(Notification.EXTRA_SUB_TEXT))
  }

  @Test
  fun eachActivityHasItsOwnNotification() {
    LiveUpdateReceive.handleFcmData(context, message("start", id = "order_1"))
    LiveUpdateReceive.handleFcmData(context, message("start", id = "order_2"))
    assertEquals(2, shadowOf(manager).allNotifications.size)
    assertNotNull(posted("order_1"))
    assertNotNull(posted("order_2"))
  }

  @Test
  fun anUpdateWithNoTextIsIgnored() {
    // Nothing to draw (title and body both missing): do not post an empty ongoing notification.
    assertTrue(LiveUpdateReceive.handleFcmData(context, message("update", title = null, body = null)))
    assertNull(posted())
  }

  @Test
  fun endWithAFinalStateLeavesADismissibleNotificationThatClearsItself() {
    LiveUpdateReceive.handleFcmData(context, message("start"))
    LiveUpdateReceive.handleFcmData(context, message("end", title = "Delivered", body = "Enjoy your meal", progress = "100"))

    assertEquals("replaced in place, not added", 1, shadowOf(manager).allNotifications.size)
    val n = posted()!!
    assertEquals("Delivered", n.text(Notification.EXTRA_TITLE))
    assertFalse("no longer ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    assertTrue("tapping or swiping clears it", n.flags and Notification.FLAG_AUTO_CANCEL != 0)
    assertEquals("clears itself after 10 minutes", 10 * 60 * 1000L, n.timeoutAfter)
    assertEquals("no progress bar on the final state", 0, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
  }

  @Test
  fun endWithNoContentRemovesTheNotification() {
    LiveUpdateReceive.handleFcmData(context, message("start"))
    assertNotNull(posted())

    assertTrue(LiveUpdateReceive.handleFcmData(context, message("end", title = null, body = null, progress = null)))
    assertNull(posted())
  }

  @Test
  fun aDismissedActivityStaysGoneUntilItIsStartedAgain() {
    LiveUpdateReceive.handleFcmData(context, message("start"))
    LiveUpdateStore(context).markDismissed("order_8392") // what the dismiss receiver records
    manager.cancelAll()

    LiveUpdateReceive.handleFcmData(context, message("update", title = "Still going"))
    assertNull("an update after a dismissal must not bring it back", posted())

    LiveUpdateReceive.handleFcmData(context, message("end", title = null, body = null, progress = null))
    LiveUpdateReceive.handleFcmData(context, message("start", title = "A new one"))
    assertEquals("A new one", posted()!!.text(Notification.EXTRA_TITLE))
  }

  @Test
  fun aLateUpdateAfterTheEndDoesNotBringTheLiveUpdateBack() {
    LiveUpdateReceive.handleFcmData(context, message("start"))
    LiveUpdateReceive.handleFcmData(context, message("end", title = "Delivered", body = "Enjoy", progress = null))

    // FCM can deliver late and out of order: this update was sent before the end.
    LiveUpdateReceive.handleFcmData(context, message("update", title = "Still going", progress = "90"))

    val n = posted()!!
    assertEquals("Delivered", n.text(Notification.EXTRA_TITLE))
    assertFalse("must not become an ongoing notification again", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
  }

  @Test
  fun aStartAfterAnEndDrawsAgain() {
    LiveUpdateReceive.handleFcmData(context, message("end", title = null, body = null, progress = null))
    LiveUpdateReceive.handleFcmData(context, message("start", title = "Again"))
    assertEquals("Again", posted()!!.text(Notification.EXTRA_TITLE))
  }

  @Test
  fun withoutASavedConfigurationNothingIsDrawnButTheMessageIsStillConsumed() {
    context.getSharedPreferences("com.comraid.sdk_config", Context.MODE_PRIVATE).edit().clear().commit()
    // Consumed, so it is not shown to the host as an ordinary data-only message.
    assertTrue(LiveUpdateReceive.handleFcmData(context, message("start")))
    assertTrue(shadowOf(manager).allNotifications.isEmpty())
  }
}
