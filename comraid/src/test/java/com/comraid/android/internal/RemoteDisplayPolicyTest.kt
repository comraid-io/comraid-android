package com.comraid.android.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam: whether the SDK auto-draws a remote Notification into the system tray.
 * Spec / ADR 0006: data_only never; non–data-only FG default on with initialize opt-out.
 */
class RemoteDisplayPolicyTest {
  private val alertData = mapOf(
    "cr_delivery_id" to "d1",
    "cr_title" to "Hello",
    "cr_body" to "World",
  )

  private val dataOnlyData = alertData + mapOf("cr_data_only" to "true")

  private val actionsData = alertData + mapOf(
    "cr_actions" to """[{"id":"accept","text":"Accept"}]""",
  )

  private val dataOnlyWithActions = dataOnlyData + mapOf(
    "cr_actions" to """[{"id":"accept","text":"Accept"}]""",
  )

  @Test
  fun data_only_never_auto_drawn_even_with_title_and_actions() {
    assertFalse(
      RemoteDisplayPolicy.shouldAutoDisplay(
        data = dataOnlyData,
        hasFcmNotificationBlock = false,
        appInForeground = true,
        displayForegroundNotifications = true,
      ),
    )
    assertFalse(
      RemoteDisplayPolicy.shouldAutoDisplay(
        data = dataOnlyWithActions,
        hasFcmNotificationBlock = false,
        appInForeground = false,
        displayForegroundNotifications = true,
      ),
    )
  }

  @Test
  fun foreground_alert_shows_by_default_even_with_fcm_notification_block() {
    assertTrue(
      RemoteDisplayPolicy.shouldAutoDisplay(
        data = alertData,
        hasFcmNotificationBlock = true,
        appInForeground = true,
        displayForegroundNotifications = true,
      ),
    )
  }

  @Test
  fun foreground_opt_out_suppresses_sdk_display() {
    assertFalse(
      RemoteDisplayPolicy.shouldAutoDisplay(
        data = alertData,
        hasFcmNotificationBlock = true,
        appInForeground = true,
        displayForegroundNotifications = false,
      ),
    )
    assertFalse(
      RemoteDisplayPolicy.shouldAutoDisplay(
        data = actionsData,
        hasFcmNotificationBlock = false,
        appInForeground = true,
        displayForegroundNotifications = false,
      ),
    )
  }

  @Test
  fun background_fcm_notification_block_does_not_double_draw() {
    assertFalse(
      RemoteDisplayPolicy.shouldAutoDisplay(
        data = alertData,
        hasFcmNotificationBlock = true,
        appInForeground = false,
        displayForegroundNotifications = true,
      ),
    )
  }

  @Test
  fun background_data_message_alert_is_sdk_drawn() {
    assertTrue(
      RemoteDisplayPolicy.shouldAutoDisplay(
        data = alertData,
        hasFcmNotificationBlock = false,
        appInForeground = false,
        displayForegroundNotifications = true,
      ),
    )
  }

  @Test
  fun non_data_only_actions_still_sdk_drawn_in_background() {
    assertTrue(
      RemoteDisplayPolicy.shouldAutoDisplay(
        data = actionsData,
        hasFcmNotificationBlock = false,
        appInForeground = false,
        displayForegroundNotifications = true,
      ),
    )
  }

  @Test
  fun background_opt_out_still_shows_when_app_not_foreground() {
    // displayForegroundNotifications only gates the foreground path.
    assertTrue(
      RemoteDisplayPolicy.shouldAutoDisplay(
        data = alertData,
        hasFcmNotificationBlock = false,
        appInForeground = false,
        displayForegroundNotifications = false,
      ),
    )
  }

  @Test
  fun blank_title_never_drawn() {
    assertFalse(
      RemoteDisplayPolicy.shouldAutoDisplay(
        data = mapOf("cr_delivery_id" to "d1", "cr_body" to "only body"),
        hasFcmNotificationBlock = false,
        appInForeground = true,
        displayForegroundNotifications = true,
      ),
    )
  }
}
