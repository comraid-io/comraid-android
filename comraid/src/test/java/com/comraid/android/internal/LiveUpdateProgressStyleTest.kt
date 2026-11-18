package com.comraid.android.internal

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.comraid.android.ComRaidConfiguration
import org.junit.Assert.assertEquals
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
 * Seam: on Android 16 (API 36) the live update uses the platform progress style and asks to be
 * promoted to a live update. Below that it stays a plain ongoing notification (LiveUpdateReceiveTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class LiveUpdateProgressStyleTest {
  // The platform stores setRequestPromotedOngoing(true) as this extra (the setter needs API 36.1).
  private val REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

  private lateinit var context: Context
  private lateinit var manager: NotificationManager

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    manager = context.getSystemService(NotificationManager::class.java)
    shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    LiveUpdateStore(context).clearForTests()
    SdkConfigStore(context).save(
      ComRaidConfiguration(appId = "cr_app_test", smallIconResourceId = android.R.drawable.sym_def_app_icon),
    )
  }

  private fun start(progress: String?, status: String?) = LiveUpdateReceive.handleFcmData(
    context,
    buildMap {
      put("cr_activity_id", "order_8392")
      put("cr_activity_type", "delivery")
      put("cr_activity_op", "start")
      put("cr_activity_title", "On the way")
      put("cr_activity_body", "7 min")
      progress?.let { put("cr_activity_progress", it) }
      status?.let { put("cr_activity_status_text", it) }
    },
  )

  private fun posted(): Notification =
    shadowOf(manager).getNotification("order_8392", LiveUpdateNotifier.NOTIFICATION_ID)!!

  @Test
  fun usesTheProgressStyleAndAsksToBePromoted() {
    assertTrue(start(progress = "40", status = "Arriving soon"))

    val n = posted()
    assertEquals("android.app.Notification\$ProgressStyle", n.extras.getString(Notification.EXTRA_TEMPLATE))
    assertEquals(40, n.extras.getInt(Notification.EXTRA_PROGRESS))
    assertTrue("asks to be a promoted live update", n.extras.getBoolean(REQUEST_PROMOTED_ONGOING))
    assertEquals("Arriving soon", n.shortCriticalText)
    assertTrue("still ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    assertNotNull("still reports a swipe", n.deleteIntent)
    assertEquals("On the way", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
  }

  @Test
  fun theActivityTypeIsStillStoredSoItCanBeListed() {
    assertTrue(start(progress = "40", status = null))
    assertEquals(
      listOf(com.comraid.android.ComRaidLiveActivity("order_8392", "delivery")),
      LiveUpdateNotifier.active(context),
    )
  }

  @Test
  fun withoutProgressItIsStillPromotedButHasNoProgressStyle() {
    assertTrue(start(progress = null, status = null))

    val n = posted()
    assertTrue(n.extras.getBoolean(REQUEST_PROMOTED_ONGOING))
    assertEquals(null, n.shortCriticalText)
    assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
  }
}
