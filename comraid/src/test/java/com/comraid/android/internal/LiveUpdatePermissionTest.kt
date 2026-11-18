package com.comraid.android.internal

import android.Manifest
import android.app.NotificationManager
import android.content.Context
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

/** Seam: a live update needs the same notification permission as any notification (Android 13+ asks at runtime). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LiveUpdatePermissionTest {
  private lateinit var context: Context
  private lateinit var manager: NotificationManager

  private val start = mapOf(
    "cr_activity_id" to "order_8392",
    "cr_activity_type" to "delivery",
    "cr_activity_op" to "start",
    "cr_activity_title" to "On the way",
  )

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    manager = context.getSystemService(NotificationManager::class.java)
    LiveUpdateStore(context).clearForTests()
    SdkConfigStore(context).save(
      ComRaidConfiguration(appId = "cr_app_test", smallIconResourceId = android.R.drawable.sym_def_app_icon),
    )
  }

  @Test
  fun withThePermissionDeniedNothingIsDrawnButTheMessageIsConsumed() {
    shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

    assertTrue(LiveUpdateReceive.handleFcmData(context, start))
    assertTrue(shadowOf(manager).allNotifications.isEmpty())
  }

  @Test
  fun withThePermissionGrantedItIsDrawn() {
    shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    assertTrue(LiveUpdateReceive.handleFcmData(context, start))
    assertEquals(1, shadowOf(manager).allNotifications.size)
  }

  @Test
  fun withNotificationsTurnedOffInSettingsNothingIsDrawn() {
    shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    shadowOf(manager).setNotificationsEnabled(false)

    assertTrue(LiveUpdateReceive.handleFcmData(context, start))
    assertTrue(shadowOf(manager).allNotifications.isEmpty())
  }
}
