package com.comraid.android.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AckQueueTest {
  private lateinit var storage: InMemoryAckQueueStore
  private lateinit var transport: RecordingTransport
  private lateinit var queue: AckQueue

  @Before
  fun setUp() {
    storage = InMemoryAckQueueStore()
    transport = RecordingTransport()
    queue = AckQueue(
      apiBaseUrl = "https://api.example.com",
      store = storage,
      transport = transport,
      clock = { 1_000_000L },
    )
  }

  @Test
  fun enqueueFromPayload_persists_delivered_ack() {
    val enqueued = queue.enqueueFromPayload(
      kind = AckKind.DELIVERED,
      data = mapOf(
        "cr_delivery_id" to "d1",
        "cr_attribution_token" to "t1",
      ),
    )

    assertTrue(enqueued)
    val raw = storage.get(queue.storageKey())!!
    val items = JSONArray(raw)
    assertEquals(1, items.length())
    assertEquals("delivered", items.getJSONObject(0).getString("kind"))
    assertEquals("d1", items.getJSONObject(0).getString("deliveryId"))
  }

  @Test
  fun flush_posts_delivered_and_clears_queue_on_success() {
    transport.nextResponse = HttpResponse(statusCode = 200, body = """{"delivery_id":"d1","status":"delivered"}""")
    queue.enqueueFromPayload(
      kind = AckKind.DELIVERED,
      data = mapOf("cr_delivery_id" to "d1", "cr_attribution_token" to "t1"),
    )

    queue.flush()

    val request = transport.requests.single()
    assertEquals("POST", request.method)
    assertEquals("/deliveries/d1/delivered", request.path)
    assertEquals("t1", JSONObject(request.body!!).getString("attribution_token"))
    assertEquals("[]", storage.get(queue.storageKey()))
  }

  @Test
  fun flush_posts_opened_with_optional_action_id() {
    transport.nextResponse = HttpResponse(statusCode = 200, body = "{}")
    queue.enqueueFromPayload(
      kind = AckKind.OPENED,
      data = mapOf("cr_delivery_id" to "d2", "cr_attribution_token" to "t2"),
      actionId = "accept",
    )

    queue.flush()

    val request = transport.requests.single()
    assertEquals("/deliveries/d2/opened", request.path)
    assertTrue(request.body!!.contains("\"action_id\":\"accept\""))
    assertTrue(request.body!!.contains("\"attribution_token\":\"t2\""))
  }

  @Test
  fun flush_retries_on_503_and_keeps_event() {
    transport.nextResponse = HttpResponse(statusCode = 503, body = "nope")
    queue.enqueueFromPayload(
      kind = AckKind.OPENED,
      data = mapOf("cr_delivery_id" to "d3", "cr_attribution_token" to "t3"),
    )

    queue.flush()

    assertEquals(1, transport.requests.size)
    val remaining = JSONArray(storage.get(queue.storageKey())!!)
    assertEquals(1, remaining.length())
    assertEquals("d3", remaining.getJSONObject(0).getString("deliveryId"))
    assertEquals(1, remaining.getJSONObject(0).getInt("attempts"))
  }

  @Test
  fun flush_honors_retry_after_seconds_on_429() {
    var now = 1_000_000L
    queue = AckQueue(
      apiBaseUrl = "https://api.example.com",
      store = storage,
      transport = transport,
      clock = { now },
    )
    transport.nextResponse = HttpResponse(
      statusCode = 429,
      body = "rate",
      headers = mapOf("Retry-After" to "30"),
    )
    queue.enqueueFromPayload(
      kind = AckKind.DELIVERED,
      data = mapOf("cr_delivery_id" to "d4", "cr_attribution_token" to "t4"),
    )

    queue.flush()

    val remaining = JSONArray(storage.get(queue.storageKey())!!)
    assertEquals(1_030_000L, remaining.getJSONObject(0).getLong("nextAttemptAt"))

    transport.requests.clear()
    transport.nextResponse = HttpResponse(statusCode = 200, body = "{}")
    now = 1_020_000L
    queue.flush()
    assertTrue(transport.requests.isEmpty())

    now = 1_030_000L
    queue.flush()
    assertEquals(1, transport.requests.size)
    assertEquals("[]", storage.get(queue.storageKey()))
  }

  @Test
  fun flush_drops_terminal_404() {
    transport.nextResponse = HttpResponse(statusCode = 404, body = "missing")
    queue.enqueueFromPayload(
      kind = AckKind.DELIVERED,
      data = mapOf("cr_delivery_id" to "d5", "cr_attribution_token" to "t5"),
    )

    queue.flush()

    assertEquals("[]", storage.get(queue.storageKey()))
  }

  @Test
  fun enqueueFromPayload_returns_false_without_attribution() {
    assertEquals(
      false,
      queue.enqueueFromPayload(AckKind.DELIVERED, mapOf("foo" to "bar")),
    )
    assertEquals(null, storage.get(queue.storageKey()))
  }

  private class InMemoryAckQueueStore : AckQueueStore {
    private val map = mutableMapOf<String, String>()

    override fun get(key: String): String? = map[key]

    override fun set(key: String, value: String) {
      map[key] = value
    }
  }

  private class RecordingTransport : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var nextResponse: HttpResponse? = null

    override fun execute(request: HttpRequest): HttpResponse {
      requests += request
      return nextResponse ?: error("No response configured")
    }
  }
}
