package com.comraid.android.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AckQueueTest {
  private lateinit var storage: InMemoryAckQueueStore
  private lateinit var transport: RecordingTransport
  private lateinit var scheduler: RecordingFlushScheduler
  private lateinit var queue: AckQueue

  @Before
  fun setUp() {
    storage = InMemoryAckQueueStore()
    transport = RecordingTransport()
    scheduler = RecordingFlushScheduler()
    queue = AckQueue(
      apiBaseUrl = "https://api.example.com",
      store = storage,
      transport = transport,
      clock = { 1_000_000L },
      flushScheduler = scheduler,
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
  fun flushAsync_sends_off_the_calling_thread() {
    val sent = java.util.concurrent.CountDownLatch(1)
    var sendThread: Thread? = null
    val asyncQueue = AckQueue(
      apiBaseUrl = "https://api.example.com",
      store = storage,
      transport = HttpTransport {
        sendThread = Thread.currentThread()
        sent.countDown()
        HttpResponse(statusCode = 200, body = "{}")
      },
      clock = { 1_000_000L },
    )
    asyncQueue.enqueueFromPayload(
      kind = AckKind.OPENED,
      data = mapOf("cr_delivery_id" to "d1", "cr_attribution_token" to "t1"),
    )

    asyncQueue.flushAsync()

    assertTrue(sent.await(5, java.util.concurrent.TimeUnit.SECONDS))
    assertTrue(sendThread !== Thread.currentThread())
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

  @Test
  fun test_send_payload_without_delivery_ids_makes_no_ack_request() {
    // Test sends omit cr_delivery_id / cr_attribution_token; receive still runs but must not ACK.
    val enqueued = queue.enqueueFromPayload(
      AckKind.DELIVERED,
      mapOf(
        "cr_title" to "Test",
        "cr_body" to "Hello",
      ),
    )
    assertEquals(false, enqueued)
    assertEquals(null, storage.get(queue.storageKey()))
    queue.flush()
    assertEquals(0, transport.requests.size)

    assertEquals(
      false,
      queue.enqueueFromPayload(
        AckKind.OPENED,
        mapOf("cr_delivery_id" to "d-only"), // token missing
      ),
    )
    queue.flush()
    assertEquals(0, transport.requests.size)
  }

  @Test
  fun enqueue_schedules_workmanager_flush() {
    queue.enqueueFromPayload(
      AckKind.DELIVERED,
      mapOf("cr_delivery_id" to "d-sched", "cr_attribution_token" to "t"),
    )
    assertEquals(1, scheduler.scheduleCount.get())
  }

  @Test
  fun enqueue_during_flush_survives_final_save() {
    val sendStarted = CountDownLatch(1)
    val allowSend = CountDownLatch(1)
    val blocking = AckQueue(
      apiBaseUrl = "https://api.example.com",
      store = storage,
      transport = HttpTransport {
        sendStarted.countDown()
        assertTrue(allowSend.await(5, TimeUnit.SECONDS))
        HttpResponse(statusCode = 200, body = "{}")
      },
      clock = { 1_000_000L },
      flushScheduler = scheduler,
    )
    blocking.enqueueFromPayload(
      AckKind.DELIVERED,
      mapOf("cr_delivery_id" to "d-flush", "cr_attribution_token" to "t1"),
    )

    val flushDone = CountDownLatch(1)
    Thread {
      blocking.flush()
      flushDone.countDown()
    }.start()
    assertTrue(sendStarted.await(5, TimeUnit.SECONDS))

    blocking.enqueueFromPayload(
      AckKind.OPENED,
      mapOf("cr_delivery_id" to "d-during", "cr_attribution_token" to "t2"),
    )
    allowSend.countDown()
    assertTrue(flushDone.await(5, TimeUnit.SECONDS))

    val remaining = JSONArray(storage.get(blocking.storageKey())!!)
    assertEquals(1, remaining.length())
    assertEquals("d-during", remaining.getJSONObject(0).getString("deliveryId"))
    assertEquals("opened", remaining.getJSONObject(0).getString("kind"))
  }

  @Test
  fun two_concurrent_flushes_send_each_ack_once() {
    val sendCount = AtomicInteger(0)
    val holdSend = CountDownLatch(1)
    val sendStarted = CountDownLatch(1)
    val concurrent = AckQueue(
      apiBaseUrl = "https://api.example.com",
      store = storage,
      transport = HttpTransport {
        sendCount.incrementAndGet()
        sendStarted.countDown()
        assertTrue(holdSend.await(5, TimeUnit.SECONDS))
        HttpResponse(statusCode = 200, body = "{}")
      },
      clock = { 1_000_000L },
      flushScheduler = scheduler,
    )
    concurrent.enqueueFromPayload(
      AckKind.DELIVERED,
      mapOf("cr_delivery_id" to "d-once", "cr_attribution_token" to "t"),
    )

    val done = CountDownLatch(2)
    repeat(2) {
      Thread {
        concurrent.flush()
        done.countDown()
      }.start()
    }
    assertTrue(sendStarted.await(5, TimeUnit.SECONDS))
    // Give the second flush a chance to race; it must not start another send.
    Thread.sleep(100)
    holdSend.countDown()
    assertTrue(done.await(5, TimeUnit.SECONDS))
    assertEquals(1, sendCount.get())
    assertEquals("[]", storage.get(concurrent.storageKey()))
  }

  @Test
  fun duplicate_opened_is_deduped_by_delivery_id() {
    transport.nextResponse = HttpResponse(statusCode = 200, body = "{}")
    val data = mapOf("cr_delivery_id" to "d-open", "cr_attribution_token" to "t")
    queue.enqueueFromPayload(AckKind.OPENED, data, actionId = null)
    queue.enqueueFromPayload(AckKind.OPENED, data, actionId = "accept")

    val remaining = JSONArray(storage.get(queue.storageKey())!!)
    assertEquals(1, remaining.length())
    assertEquals("opened", remaining.getJSONObject(0).getString("kind"))
    assertEquals("d-open", remaining.getJSONObject(0).getString("deliveryId"))
  }

  @Test
  fun already_sent_ack_is_not_reenqueued_within_ttl() {
    transport.nextResponse = HttpResponse(statusCode = 200, body = "{}")
    val data = mapOf("cr_delivery_id" to "d-sent", "cr_attribution_token" to "t")
    queue.enqueueFromPayload(AckKind.OPENED, data)
    queue.flush()
    assertEquals("[]", storage.get(queue.storageKey()))

    queue.enqueueFromPayload(AckKind.OPENED, data, actionId = "accept")
    assertEquals("[]", storage.get(queue.storageKey()))
    assertEquals(1, transport.requests.size)
  }

  @Test
  fun claimed_ack_stays_durable_until_send_succeeds_and_survives_crash_window() {
    val sendStarted = CountDownLatch(1)
    val allowSend = CountDownLatch(1)
    val midFlight = AckQueue(
      apiBaseUrl = "https://api.example.com",
      store = storage,
      transport = HttpTransport {
        sendStarted.countDown()
        assertTrue(allowSend.await(5, TimeUnit.SECONDS))
        HttpResponse(statusCode = 200, body = "{}")
      },
      clock = { 1_000_000L },
      flushScheduler = scheduler,
    )
    midFlight.enqueueFromPayload(
      AckKind.DELIVERED,
      mapOf("cr_delivery_id" to "d-crash", "cr_attribution_token" to "t"),
    )

    val flushDone = CountDownLatch(1)
    Thread {
      midFlight.flush()
      flushDone.countDown()
    }.start()
    assertTrue(sendStarted.await(5, TimeUnit.SECONDS))

    // Process-death window: claimed for send but not yet accepted — still durable.
    val midFlightRaw = storage.get(midFlight.storageKey())!!
    val midFlightItems = JSONArray(midFlightRaw)
    assertEquals(1, midFlightItems.length())
    assertEquals("d-crash", midFlightItems.getJSONObject(0).getString("deliveryId"))

    // Snapshot mid-flight state, then let the in-process flush finish so the
    // process-wide flushInFlight flag clears (a real crash starts a new process).
    val snapshot = InMemoryAckQueueStore()
    snapshot.set(midFlight.storageKey(), midFlightRaw)
    allowSend.countDown()
    assertTrue(flushDone.await(5, TimeUnit.SECONDS))

    transport.nextResponse = HttpResponse(statusCode = 200, body = "{}")
    val restarted = AckQueue(
      apiBaseUrl = "https://api.example.com",
      store = snapshot,
      transport = transport,
      clock = { 1_000_000L },
    )
    restarted.flush()
    assertEquals(1, transport.requests.size)
    assertEquals("/deliveries/d-crash/delivered", transport.requests.single().path)
    assertEquals("[]", snapshot.get(restarted.storageKey()))
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

  private class RecordingFlushScheduler : AckFlushScheduler {
    val scheduleCount = AtomicInteger(0)
    override fun schedule() {
      scheduleCount.incrementAndGet()
    }
  }
}
