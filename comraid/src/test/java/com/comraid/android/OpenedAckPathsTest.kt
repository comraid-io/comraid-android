package com.comraid.android

import com.comraid.android.internal.HttpRequest
import com.comraid.android.internal.HttpResponse
import com.comraid.android.internal.HttpTransport
import com.comraid.android.internal.InMemorySubscriptionStore
import com.comraid.android.internal.TokenProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenedAckPathsTest {
  private lateinit var transport: RecordingTransport

  @Before
  fun setUp() {
    ComRaid.resetForTests()
    transport = RecordingTransport()
    ComRaid.configureForTests(
      configuration = ComRaidConfiguration(
        appId = "cr_app_test",
        smallIconResourceId = 1,
      ),
      transport = transport,
      tokenProvider = FakeTokenProvider("fcm-token-1"),
      subscriptionStore = InMemorySubscriptionStore(),
    )
  }

  @Test
  fun cold_start_body_tap_posts_opened_ack_and_stashes_initial_notification() {
    transport.nextResponse = HttpResponse(200, """{"delivery_id":"d-cold","status":"opened"}""")
    val data = mapOf(
      "cr_delivery_id" to "d-cold",
      "cr_attribution_token" to "tok-cold",
      "cr_title" to "Hello",
      "cr_body" to "World",
    )

    val event = ComRaid.handleNotificationOpen(data, actionId = null, coldStart = true)

    assertEquals("d-cold", event.deliveryId)
    assertNull(event.actionId)
    val initial = ComRaid.getInitialNotification()
    assertEquals("d-cold", initial?.deliveryId)
    assertNull(initial?.actionId)
    assertNull(ComRaid.getInitialNotification())

    val request = transport.awaitRequests(1).single()
    assertEquals("/deliveries/d-cold/opened", request.path)
    val body = JSONObject(request.body!!)
    assertEquals("tok-cold", body.getString("attribution_token"))
    assertTrue(!body.has("action_id") || body.isNull("action_id"))
  }

  @Test
  fun deep_link_tap_surfaces_deepLink_on_opened_listener_and_initial_notification() {
    transport.nextResponse = HttpResponse(200, """{"delivery_id":"d-link","status":"opened"}""")
    val data = mapOf(
      "cr_delivery_id" to "d-link",
      "cr_attribution_token" to "tok-link",
      "cr_title" to "Order",
      "cr_deep_link" to "myapp://orders/42",
    )
    var opened: RemoteMessageEvent? = null
    ComRaid.addNotificationOpenedListener { opened = it }

    val event = ComRaid.handleNotificationOpen(data, actionId = null, coldStart = true)

    assertEquals("myapp://orders/42", event.deepLink)
    assertEquals("myapp://orders/42", opened?.deepLink)
    assertEquals("myapp://orders/42", ComRaid.getInitialNotification()?.deepLink)
  }

  @Test
  fun action_button_tap_posts_opened_ack_with_action_id() {
    transport.nextResponse = HttpResponse(200, """{"delivery_id":"d-act","status":"opened"}""")
    val data = mapOf(
      "cr_delivery_id" to "d-act",
      "cr_attribution_token" to "tok-act",
      "cr_title" to "Rematch?",
    )

    val event = ComRaid.handleNotificationOpen(data, actionId = "accept", coldStart = false)

    assertEquals("accept", event.actionId)
    val request = transport.awaitRequests(1).single()
    assertEquals("/deliveries/d-act/opened", request.path)
    assertEquals("accept", JSONObject(request.body!!).getString("action_id"))
  }

  private class RecordingTransport : HttpTransport {
    val requests: MutableList<HttpRequest> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var nextResponse: HttpResponse? = null

    fun awaitRequests(count: Int, timeoutMs: Long = 5_000): List<HttpRequest> {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (requests.size < count && System.currentTimeMillis() < deadline) Thread.sleep(10)
      return requests.toList()
    }

    override fun execute(request: HttpRequest): HttpResponse {
      requests.add(request)
      return nextResponse ?: HttpResponse(500, "no response")
    }
  }

  private class FakeTokenProvider(var token: String) : TokenProvider {
    override fun currentToken(): String = token
  }
}
