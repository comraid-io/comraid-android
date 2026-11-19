package com.comraid.android.internal

import android.app.NotificationManager
import android.content.Context
import com.comraid.android.ComRaid
import com.comraid.android.ComRaidConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Seam: what the FCM messaging service runs for every message ([FcmReceive], which takes plain data
 * because Firebase is the host's dependency). A Live activity message must be drawn as a live update and never
 * reach the host's listeners or the Delivery ACK path as an ordinary data-only message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FcmReceiveLiveUpdateTest {
  private lateinit var context: Context
  private lateinit var manager: NotificationManager

  @Before
  fun setUp() {
    ComRaid.resetForTests()
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

  private fun deliver(data: Map<String, String>) {
    FcmReceive.handle(
      context = context,
      data = data,
      notificationTitle = null,
      notificationBody = null,
      hasFcmNotificationBlock = false,
      appInForeground = false,
    )
  }

  @Test
  fun aLiveUpdateIsDrawnAndNotHandedToTheHostAsAMessage() {
    val received = mutableListOf<Map<String, String>>()
    ComRaid.addForegroundMessageListener { received += it.data }
    ComRaid.addBackgroundMessageListener { received += it.data }

    deliver(
      mapOf(
        "cr_activity_id" to "order_8392",
        "cr_activity_type" to "delivery",
        "cr_activity_op" to "start",
        "cr_activity_title" to "On the way",
        "cr_activity_body" to "7 min",
        // The server also marks it data-only, so older SDKs ignore it.
        "cr_data_only" to "true",
        "cr_title" to "On the way",
        "cr_body" to "7 min",
      ),
    )

    assertEquals(1, shadowOf(manager).allNotifications.size)
    assertTrue("the host must not see a live update as a message, got $received", received.isEmpty())
  }

  @Test
  fun anOrdinaryMessageStillGoesTheOrdinaryWay() {
    val received = mutableListOf<Map<String, String>>()
    ComRaid.addBackgroundMessageListener { received += it.data }

    deliver(mapOf("cr_title" to "Hi", "cr_body" to "There", "order_id" to "42"))

    assertEquals("an ordinary message reaches host listeners", 1, received.size)
  }
}
