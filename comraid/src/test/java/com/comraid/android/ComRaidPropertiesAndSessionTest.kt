package com.comraid.android

import com.comraid.android.internal.DeviceInfoProvider
import com.comraid.android.internal.HttpRequest
import com.comraid.android.internal.HttpResponse
import com.comraid.android.internal.HttpTransport
import com.comraid.android.internal.InMemorySubscriptionStore
import com.comraid.android.internal.TokenProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComRaidPropertiesAndSessionTest {
  private lateinit var transport: RecordingTransport
  private lateinit var store: InMemorySubscriptionStore
  private lateinit var tokens: FakeTokenProvider
  private var nowMs = 1_000_000L

  private val auto = DeviceProperties(
    appVersion = "9.9.9",
    sdkVersion = "0.1.0",
    model = "Pixel 8",
    os = "Android",
    osVersion = "14",
    locale = "en-US",
    timezone = "America/New_York",
    language = "en",
    country = "US",
  )

  @Before
  fun setUp() {
    ComRaid.resetForTests()
    transport = RecordingTransport()
    store = InMemorySubscriptionStore()
    tokens = FakeTokenProvider("fcm-token-1")
    nowMs = 1_000_000L
    ComRaid.configureForTests(
      configuration = ComRaidConfiguration(
        appId = "cr_app_test",
        smallIconResourceId = 1,
      ),
      transport = transport,
      tokenProvider = tokens,
      subscriptionStore = store,
      deviceInfoProvider = DeviceInfoProvider { auto },
      clock = { nowMs },
    )
  }

  @Test
  fun register_sends_auto_collected_properties_without_app_call() {
    transport.nextResponse = HttpResponse(
      statusCode = 201,
      body = """{"subscription_id":"sub-new","subscription_credential":"cred-new","channel":"push","anonymous":true,"platform":"android","provider":"fcm"}""",
    )

    ComRaid.register()

    val body = JSONObject(transport.requests.single().body!!)
    assertEquals("9.9.9", body.getString("app_version"))
    assertEquals("0.1.0", body.getString("sdk_version"))
    assertEquals("en-US", body.getString("locale"))
    assertEquals("America/New_York", body.getString("timezone"))
    assertEquals("en", body.getString("language"))
    assertEquals("US", body.getString("country"))
    val device = body.getJSONObject("device")
    assertEquals("Android", device.getString("os"))
    assertEquals("14", device.getString("os_version"))
    assertEquals("Pixel 8", device.getString("model"))
  }

  @Test
  fun setDeviceProperties_overrides_auto_collected_fields() {
    ComRaid.setDeviceProperties(
      DeviceProperties(
        appVersion = "2.1.0",
        model = "Custom Model",
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
    assertEquals("0.1.0", body.getString("sdk_version"))
    assertEquals("en-US", body.getString("locale"))
    val device = body.getJSONObject("device")
    assertEquals("Custom Model", device.getString("model"))
    assertEquals("14", device.getString("os_version"))
    assertEquals("Google", device.getString("manufacturer"))
  }

  @Test
  fun foreground_after_debounce_posts_session_with_properties() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")
    transport.nextResponse = HttpResponse(
      statusCode = 200,
      body = """{"subscription_id":"sub-1","session_count":2,"last_seen_at":"2026-01-01T00:00:00Z"}""",
    )

    ComRaid.handleAppBackgroundForTests()
    nowMs += 30 * 60 * 1000L + 1
    ComRaid.handleAppForegroundForTests()

    val request = transport.awaitRequests(1).single()
    assertEquals("POST", request.method)
    assertEquals("/mobile/subscriptions/sub-1/session", request.path)
    val body = JSONObject(request.body!!)
    assertEquals("cred-1", body.getString("subscription_credential"))
    assertEquals("9.9.9", body.getString("app_version"))
    assertEquals("0.1.0", body.getString("sdk_version"))
    assertEquals("America/New_York", body.getString("timezone"))
    assertEquals("Pixel 8", body.getJSONObject("device").getString("model"))
  }

  @Test
  fun foreground_within_debounce_skips_session() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")

    ComRaid.handleAppBackgroundForTests()
    nowMs += 5 * 60 * 1000L
    ComRaid.handleAppForegroundForTests()

    assertTrue(transport.requests.isEmpty())
  }

  @Test
  fun register_then_immediate_foreground_skips_session() {
    transport.nextResponse = HttpResponse(
      statusCode = 201,
      body = """{"subscription_id":"sub-new","subscription_credential":"cred-new","channel":"push","anonymous":true,"platform":"android","provider":"fcm"}""",
    )
    ComRaid.register()
    transport.requests.clear()

    ComRaid.handleAppForegroundForTests()

    assertTrue(transport.requests.isEmpty())
  }

  @Test
  fun cold_start_with_existing_subscription_posts_session() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")
    transport.nextResponse = HttpResponse(
      statusCode = 200,
      body = """{"subscription_id":"sub-1","session_count":3,"last_seen_at":"2026-01-01T00:00:00Z"}""",
    )

    ComRaid.handleAppForegroundForTests()

    assertEquals("/mobile/subscriptions/sub-1/session", transport.awaitRequests(1).single().path)
  }

  private class RecordingTransport : HttpTransport {
    val requests: MutableList<HttpRequest> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var nextResponse: HttpResponse? = null

    fun awaitRequests(count: Int, timeoutMs: Long = 5_000): List<HttpRequest> {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (requests.size < count && System.currentTimeMillis() < deadline) Thread.sleep(10)
      return synchronized(requests) { requests.toList() }
    }

    override fun execute(request: HttpRequest): HttpResponse {
      requests += request
      return nextResponse ?: error("No response configured")
    }
  }

  private class FakeTokenProvider(var token: String) : TokenProvider {
    override fun currentToken(): String = token
  }
}
