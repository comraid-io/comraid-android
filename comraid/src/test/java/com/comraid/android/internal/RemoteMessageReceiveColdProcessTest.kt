package com.comraid.android.internal

import com.comraid.android.ComRaid
import com.comraid.android.ComRaidConfiguration
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Seam: FCM receive with no foreground [ComRaid] runtime (process started by messaging service).
 * ADR 0014 / delivery-tracking-analytics-v1 ticket 09.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RemoteMessageReceiveColdProcessTest {
  private lateinit var transport: RecordingTransport
  private lateinit var helpers: BackgroundAckHelpers

  @Before
  fun setUp() {
    ComRaid.resetForTests()
    transport = RecordingTransport()
    transport.nextResponse = HttpResponse(200, """{"delivery_id":"d-cold","status":"delivered"}""")
    val configuration = ComRaidConfiguration(
      appId = "cr_app_test",
      apiBaseUrl = "https://api.example.com",
      smallIconResourceId = android.R.drawable.sym_def_app_icon,
    )
    helpers = BackgroundAckHelpers(
      queue = AckQueue(
        apiBaseUrl = configuration.apiBaseUrl,
        store = InMemoryAckQueueStore(),
        transport = transport,
        flushScheduler = NoopFlushScheduler(),
      ),
      configuration = configuration,
    )
  }

  @Test
  fun cold_process_enqueues_and_flushes_delivered_ack_without_comraid_runtime() {
    val data = mapOf(
      "cr_delivery_id" to "d-cold",
      "cr_attribution_token" to "tok-cold",
      "cr_title" to "Hello",
      "cr_body" to "World",
    )
    assertTrue(
      RemoteDisplayPolicy.shouldAutoDisplay(
        data = data,
        hasFcmNotificationBlock = false,
        appInForeground = false,
        displayForegroundNotifications = true,
      ),
    )

    RemoteMessageReceive.handle(
      context = RuntimeEnvironment.getApplication(),
      helpers = helpers,
      data = data,
      hasFcmNotificationBlock = false,
      appInForeground = false,
    )

    val request = transport.awaitRequests(1).single()
    assertEquals("/deliveries/d-cold/delivered", request.path)
    val body = JSONObject(request.body!!)
    assertEquals("tok-cold", body.getString("attribution_token"))
  }

  @Test
  fun cold_process_data_only_acks_but_does_not_auto_display() {
    transport.nextResponse = HttpResponse(200, """{"delivery_id":"d-silent","status":"delivered"}""")
    val data = mapOf(
      "cr_delivery_id" to "d-silent",
      "cr_attribution_token" to "tok-silent",
      "cr_title" to "Hello",
      "cr_body" to "World",
      "cr_data_only" to "true",
    )
    assertFalse(
      RemoteDisplayPolicy.shouldAutoDisplay(
        data = data,
        hasFcmNotificationBlock = false,
        appInForeground = false,
        displayForegroundNotifications = true,
      ),
    )

    RemoteMessageReceive.handle(
      context = RuntimeEnvironment.getApplication(),
      helpers = helpers,
      data = data,
      hasFcmNotificationBlock = false,
      appInForeground = false,
    )

    val request = transport.awaitRequests(1).single()
    assertEquals("/deliveries/d-silent/delivered", request.path)
  }

  @Test
  fun sdk_config_store_survives_without_comraid_singleton() {
    val context = RuntimeEnvironment.getApplication()
    val configuration = ComRaidConfiguration(
      appId = "cr_app_persisted",
      apiBaseUrl = "https://api.example.com",
      smallIconResourceId = android.R.drawable.sym_def_app_icon,
      displayForegroundNotifications = false,
    )
    SdkConfigStore(context).save(configuration)
    ComRaid.resetForTests()

    val loaded = SdkConfigStore(context).load()!!
    assertEquals("cr_app_persisted", loaded.appId)
    assertEquals("https://api.example.com", loaded.apiBaseUrl)
    assertFalse(loaded.displayForegroundNotifications)
  }

  private class InMemoryAckQueueStore : AckQueueStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String) {
      map[key] = value
    }
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

  private class NoopFlushScheduler : AckFlushScheduler {
    override fun schedule() = Unit
  }
}
