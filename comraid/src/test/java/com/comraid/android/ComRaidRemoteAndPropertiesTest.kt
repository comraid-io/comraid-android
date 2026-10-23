package com.comraid.android

import com.comraid.android.internal.HttpRequest
import com.comraid.android.internal.HttpResponse
import com.comraid.android.internal.HttpTransport
import com.comraid.android.internal.InMemorySubscriptionStore
import com.comraid.android.internal.TokenProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComRaidRemoteAndPropertiesTest {
  private lateinit var transport: RecordingTransport
  private lateinit var store: InMemorySubscriptionStore
  private lateinit var tokens: FakeTokenProvider

  @Before
  fun setUp() {
    ComRaid.resetForTests()
    transport = RecordingTransport()
    store = InMemorySubscriptionStore()
    tokens = FakeTokenProvider("fcm-token-1")
    ComRaid.configureForTests(
      configuration = ComRaidConfiguration(
        appId = "cr_app_test",
        smallIconResourceId = 1,
      ),
      transport = transport,
      tokenProvider = tokens,
      subscriptionStore = store,
    )
  }

  @Test
  fun register_includes_device_properties_and_app_version() {
    ComRaid.setDeviceProperties(
      DeviceProperties(
        appVersion = "2.1.0",
        model = "Pixel 8",
        osVersion = "14",
        extras = mapOf("manufacturer" to "Google"),
      ),
    )
    transport.nextResponse = HttpResponse(
      statusCode = 201,
      body = """{"subscription_id":"sub-new","subscription_credential":"cred-new","channel":"push","anonymous":true,"platform":"android","provider":"fcm"}""",
    )

    ComRaid.register()

    val body = JSONObject(transport.requests.single().body!!)
    assertEquals("2.1.0", body.getString("app_version"))
    val device = body.getJSONObject("device")
    assertEquals("Pixel 8", device.getString("model"))
    assertEquals("14", device.getString("os_version"))
    assertEquals("Google", device.getString("manufacturer"))
  }

  @Test
  fun setAttributes_puts_contact_attributes_after_login() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")
    transport.enqueue(
      HttpResponse(
        200,
        """{"subscription_id":"sub-1","contact_id":"c-1","external_user_id":"user_123","anonymous":false,"verified":false}""",
      ),
      HttpResponse(200, """{"contact_id":"c-1","external_user_id":"user_123","updated_attributes":["plan"]}"""),
    )

    ComRaid.login("user_123")
    ComRaid.setAttributes(mapOf("plan" to "premium", "tier" to 2))

    val request = transport.requests[1]
    assertEquals("PUT", request.method)
    assertEquals("/mobile/subscriptions/sub-1/attributes", request.path)
    assertEquals("cr_app_test", request.headers["X-ComRaid-App-Id"])
    val body = JSONObject(request.body!!)
    assertEquals("cred-1", body.getString("subscription_credential"))
    val attrs = body.getJSONObject("attributes")
    assertEquals("premium", attrs.getString("plan"))
    assertEquals(2, attrs.getInt("tier"))
  }

  @Test
  fun setAttributes_empty_map_after_login_puts_empty_attributes_body() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")
    transport.enqueue(
      HttpResponse(
        200,
        """{"subscription_id":"sub-1","contact_id":"c-1","external_user_id":"user_123","anonymous":false,"verified":false}""",
      ),
      HttpResponse(200, """{"contact_id":"c-1","external_user_id":"user_123","updated_attributes":[]}"""),
    )

    ComRaid.login("user_123")
    ComRaid.setAttributes(emptyMap())

    val request = transport.requests[1]
    assertEquals("PUT", request.method)
    assertEquals("/mobile/subscriptions/sub-1/attributes", request.path)
    assertEquals("cr_app_test", request.headers["X-ComRaid-App-Id"])
    val body = JSONObject(request.body!!)
    assertEquals("cred-1", body.getString("subscription_credential"))
    val attrs = body.getJSONObject("attributes")
    assertEquals(0, attrs.length())
  }

  @Test
  fun setAttributes_before_login_throws_not_ready() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")

    assertThrows(ComRaidException.NotReady::class.java) {
      ComRaid.setAttributes(emptyMap())
    }
    assertTrue(transport.requests.isEmpty())
  }

  @Test
  fun acknowledgeDelivered_posts_via_ack_queue_transport() {
    transport.nextResponse = HttpResponse(200, """{"delivery_id":"d1","status":"delivered"}""")

    val ok = ComRaid.acknowledgeDelivered(
      deliveryId = "d1",
      attributionToken = "tok",
    )

    assertTrue(ok)
    val request = transport.requests.single()
    assertEquals("/deliveries/d1/delivered", request.path)
    assertEquals("tok", JSONObject(request.body!!).getString("attribution_token"))
  }

  @Test
  fun acknowledgeOpened_includes_action_id() {
    transport.nextResponse = HttpResponse(200, """{"delivery_id":"d1","status":"opened"}""")

    assertTrue(
      ComRaid.acknowledgeOpened(
        deliveryId = "d1",
        attributionToken = "tok",
        actionId = "accept",
      ),
    )
    val body = JSONObject(transport.requests.single().body!!)
    assertEquals("accept", body.getString("action_id"))
    assertEquals("/deliveries/d1/opened", transport.requests.single().path)
  }

  @Test
  fun token_refresh_preserves_subscription_id_and_sends_device_properties() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")
    ComRaid.setDeviceProperties(DeviceProperties(appVersion = "3.0.0", model = "Pixel"))
    tokens.token = "fcm-token-2"
    transport.nextResponse = HttpResponse(
      statusCode = 200,
      body = """{"subscription_id":"sub-1","anonymous":true,"platform":"android","provider":"fcm","verified":false,"token_version":2}""",
    )

    ComRaid.onPushTokenRefresh()

    assertEquals("sub-1", store.subscriptionId)
    assertEquals("cred-1", store.credential)
    val body = JSONObject(transport.requests.single().body!!)
    assertEquals("3.0.0", body.getString("app_version"))
    assertEquals("Pixel", body.getJSONObject("device").getString("model"))
    assertFalse(body.has("subscription_id")) // identity preserved via path, not new register
  }

  private class RecordingTransport : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var nextResponse: HttpResponse? = null
    private val queue = ArrayDeque<HttpResponse>()

    fun enqueue(vararg responses: HttpResponse) {
      queue.clear()
      queue.addAll(responses)
    }

    override fun execute(request: HttpRequest): HttpResponse {
      requests += request
      return when {
        queue.isNotEmpty() -> queue.removeFirst()
        nextResponse != null -> nextResponse!!
        else -> error("No response configured")
      }
    }
  }

  private class FakeTokenProvider(var token: String) : TokenProvider {
    override fun currentToken(): String = token
  }
}
