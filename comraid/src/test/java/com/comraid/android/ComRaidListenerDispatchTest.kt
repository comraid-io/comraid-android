package com.comraid.android

import com.comraid.android.internal.HttpRequest
import com.comraid.android.internal.HttpResponse
import com.comraid.android.internal.HttpTransport
import com.comraid.android.internal.InMemorySubscriptionStore
import com.comraid.android.internal.TokenProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComRaidListenerDispatchTest {
  @Before
  fun setUp() {
    ComRaid.resetForTests()
    ComRaid.configureForTests(
      configuration = ComRaidConfiguration(
        appId = "cr_app_test",
        smallIconResourceId = 1,
      ),
      transport = UnusedTransport(),
      tokenProvider = FixedTokenProvider("token"),
      subscriptionStore = InMemorySubscriptionStore(),
    )
  }

  @Test
  fun foreground_receive_fires_only_foreground_listener_once() {
    val foreground = RecordingListener()
    val background = RecordingListener()
    val opened = RecordingListener()
    ComRaid.addForegroundMessageListener(foreground)
    ComRaid.addBackgroundMessageListener(background)
    ComRaid.addNotificationOpenedListener(opened)

    ComRaid.notifyMessageReceived(sampleData("d-fg"), appInForeground = true)

    assertEquals(1, foreground.events.size)
    assertEquals("d-fg", foreground.events.single().deliveryId)
    assertEquals("Hello", foreground.events.single().title)
    assertTrue(background.events.isEmpty())
    assertTrue(opened.events.isEmpty())
  }

  @Test
  fun background_receive_fires_only_background_listener_once() {
    val foreground = RecordingListener()
    val background = RecordingListener()
    ComRaid.addForegroundMessageListener(foreground)
    ComRaid.addBackgroundMessageListener(background)

    ComRaid.notifyMessageReceived(sampleData("d-bg"), appInForeground = false)

    assertEquals(1, background.events.size)
    assertEquals("d-bg", background.events.single().deliveryId)
    assertTrue(foreground.events.isEmpty())
  }

  @Test
  fun remove_foreground_listener_stops_further_delivery() {
    val listener = RecordingListener()
    ComRaid.addForegroundMessageListener(listener)

    ComRaid.notifyMessageReceived(sampleData("d-1"), appInForeground = true)
    ComRaid.removeForegroundMessageListener(listener)
    ComRaid.notifyMessageReceived(sampleData("d-2"), appInForeground = true)

    assertEquals(listOf("d-1"), listener.events.map { it.deliveryId })
  }

  @Test
  fun remove_background_listener_stops_further_delivery() {
    val listener = RecordingListener()
    ComRaid.addBackgroundMessageListener(listener)

    ComRaid.notifyMessageReceived(sampleData("d-1"), appInForeground = false)
    ComRaid.removeBackgroundMessageListener(listener)
    ComRaid.notifyMessageReceived(sampleData("d-2"), appInForeground = false)

    assertEquals(listOf("d-1"), listener.events.map { it.deliveryId })
  }

  @Test
  fun opened_listener_fires_once_with_mapped_event() {
    val opened = RecordingListener()
    ComRaid.addNotificationOpenedListener(opened)

    val event = ComRaid.handleNotificationOpen(
      sampleData("d-open"),
      actionId = "accept",
      coldStart = false,
    )

    assertEquals("accept", event.actionId)
    assertEquals(1, opened.events.size)
    assertEquals("d-open", opened.events.single().deliveryId)
    assertEquals("accept", opened.events.single().actionId)
    assertEquals("Hello", opened.events.single().title)
  }

  @Test
  fun remove_opened_listener_stops_further_delivery() {
    val opened = RecordingListener()
    ComRaid.addNotificationOpenedListener(opened)

    ComRaid.handleNotificationOpen(sampleData("d-1"), actionId = null, coldStart = false)
    ComRaid.removeNotificationOpenedListener(opened)
    ComRaid.handleNotificationOpen(sampleData("d-2"), actionId = null, coldStart = false)

    assertEquals(listOf("d-1"), opened.events.map { it.deliveryId })
  }

  @Test
  fun opened_with_no_listeners_stashes_initial_notification_on_cold_start() {
    val event = ComRaid.handleNotificationOpen(
      sampleData("d-cold"),
      actionId = null,
      coldStart = true,
    )

    assertEquals("d-cold", event.deliveryId)
    assertEquals("d-cold", ComRaid.getInitialNotification()?.deliveryId)
    assertNull(ComRaid.getInitialNotification())
  }

  private fun sampleData(deliveryId: String): Map<String, String> = mapOf(
    "cr_delivery_id" to deliveryId,
    "cr_attribution_token" to "tok-$deliveryId",
    "cr_title" to "Hello",
    "cr_body" to "World",
  )

  private class RecordingListener : RemoteMessageListener {
    val events = mutableListOf<RemoteMessageEvent>()
    override fun onRemoteMessage(event: RemoteMessageEvent) {
      events += event
    }
  }

  private class UnusedTransport : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse =
      HttpResponse(200, """{"ok":true}""")
  }

  private class FixedTokenProvider(private val token: String) : TokenProvider {
    override fun currentToken(): String = token
  }
}
