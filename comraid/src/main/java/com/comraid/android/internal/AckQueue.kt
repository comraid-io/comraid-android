package com.comraid.android.internal

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.pow

internal enum class AckKind(val wire: String) {
  DELIVERED("delivered"),
  OPENED("opened"),
  ;

  companion object {
    fun fromWire(value: String): AckKind? = entries.firstOrNull { it.wire == value }
  }
}

internal data class QueuedAck(
  val deliveryId: String,
  val attributionToken: String,
  val kind: AckKind,
  val actionId: String? = null,
  val enqueuedAt: Long,
  val attempts: Int = 0,
  val nextAttemptAt: Long,
)

/**
 * Durable delivery ACK queue. Persists to [AckQueueStore] so background FCM /
 * receivers can enqueue and flush without a foreground [com.comraid.android.ComRaid] init.
 *
 * Enqueue and flush share [flushLock]. At most one flush runs network I/O at a time;
 * items enqueued while a flush is in flight are kept and flushed later.
 */
internal class AckQueue(
  apiBaseUrl: String,
  private val store: AckQueueStore,
  private val transport: HttpTransport,
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val maxSize: Int = DEFAULT_MAX_SIZE,
  private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
  private val maxFlush: Int = DEFAULT_MAX_FLUSH,
  private val flushScheduler: AckFlushScheduler? = null,
) {
  private val baseUrl = apiBaseUrl.trimEnd('/')

  fun storageKey(): String = "comraid:$baseUrl:ack-queue"

  fun sentStorageKey(): String = "comraid:$baseUrl:ack-sent"

  fun enqueueFromPayload(
    kind: AckKind,
    data: Map<String, String>?,
    actionId: String? = null,
  ): Boolean {
    val attribution = PushPayload.attribution(data) ?: return false
    enqueue(
      QueuedAck(
        deliveryId = attribution.deliveryId,
        attributionToken = attribution.attributionToken,
        kind = kind,
        actionId = actionId?.takeIf { it.isNotBlank() },
        enqueuedAt = clock(),
        attempts = 0,
        nextAttemptAt = clock(),
      ),
    )
    return true
  }

  fun enqueue(event: QueuedAck) {
    val accepted = synchronized(flushLock) {
      enqueueLocked(event)
    }
    if (accepted) {
      flushScheduler?.schedule()
    }
  }

  /**
   * [flush] on the SDK's ACK thread. Use from the main thread (activities, receivers,
   * `Application.onCreate`), where Android rejects network I/O.
   */
  fun flushAsync() {
    flushExecutor.execute { runCatching { flush() } }
  }

  /**
   * Blocking network flush; call from a background thread.
   * At most one flush is in flight per process; a concurrent call returns immediately.
   */
  fun flush() {
    synchronized(flushLock) {
      if (flushInFlight) return
      flushInFlight = true
    }
    try {
      flushOnce()
    } finally {
      synchronized(flushLock) {
        flushInFlight = false
      }
    }
  }

  /** @return true when the event was written to the durable queue. */
  private fun enqueueLocked(event: QueuedAck): Boolean {
    val now = clock()
    pruneSentLocked(now)
    if (wasSentRecentlyLocked(event, now)) return false

    var items = load().filter { now - it.enqueuedAt <= maxAgeMs }
    val key = eventKey(event)
    items = items.filter { eventKey(it) != key }
    items = items + event
    while (items.size > maxSize) {
      items = items.drop(1)
    }
    save(items)
    return true
  }

  private fun flushOnce() {
    val ready: List<QueuedAck>
    synchronized(flushLock) {
      val now = clock()
      pruneSentLocked(now)
      val items = load().filter { now - it.enqueuedAt <= maxAgeMs }
      ready = items.filter { it.nextAttemptAt <= now }.take(maxFlush)
      if (ready.isEmpty()) {
        save(items)
        return
      }
      val readyKeys = ready.map { eventKey(it) }.toSet()
      // Drop claimed items from the store before network I/O so concurrent enqueues survive.
      save(items.filter { eventKey(it) !in readyKeys })
    }

    val retries = mutableListOf<QueuedAck>()
    val finished = mutableListOf<QueuedAck>()
    for (event in ready) {
      when (val result = send(event)) {
        SendResult.SUCCESS, SendResult.DROP -> finished += event
        is SendResult.RETRY -> {
          val attempts = event.attempts + 1
          val nextAt = result.nextAttemptAt
            ?: (clock() + min(60_000L, (1000.0 * 2.0.pow(min(attempts, 5))).toLong()))
          retries += event.copy(attempts = attempts, nextAttemptAt = nextAt)
        }
      }
    }

    synchronized(flushLock) {
      val now = clock()
      // SUCCESS and DROP both leave the queue; both count as already-sent within TTL so
      // trampoline + consumeLaunchIntent (and action vs body) do not re-POST the same Delivery.
      for (event in finished) {
        markSentLocked(event, now)
      }

      var items = load().filter { now - it.enqueuedAt <= maxAgeMs }
      for (retry in retries) {
        val key = eventKey(retry)
        if (wasSentRecentlyLocked(retry, now)) continue
        items = items.filter { eventKey(it) != key } + retry
      }
      while (items.size > maxSize) {
        items = items.drop(1)
      }
      save(items)
    }
  }

  private fun send(event: QueuedAck): SendResult {
    val path = "/deliveries/${event.deliveryId}/${event.kind.wire}"
    val body = JSONObject().put("attribution_token", event.attributionToken)
    if (event.kind == AckKind.OPENED && !event.actionId.isNullOrBlank()) {
      body.put("action_id", event.actionId)
    }
    return try {
      val response = transport.execute(
        HttpRequest(
          method = "POST",
          path = path,
          headers = emptyMap(),
          body = body.toString(),
        ),
      )
      when {
        response.statusCode in 200..299 -> SendResult.SUCCESS
        response.statusCode == 429 || response.statusCode >= 500 -> {
          val retryAfter = response.headers.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?.trim()
          val next = if (retryAfter != null && retryAfter.all { it.isDigit() }) {
            clock() + retryAfter.toLong() * 1000L
          } else {
            null
          }
          SendResult.RETRY(next)
        }
        response.statusCode in 400..499 -> SendResult.DROP
        else -> SendResult.RETRY(null)
      }
    } catch (_: Exception) {
      SendResult.RETRY(null)
    }
  }

  private fun load(): List<QueuedAck> {
    val raw = store.get(storageKey()) ?: return emptyList()
    return try {
      val array = JSONArray(raw)
      buildList {
        for (i in 0 until array.length()) {
          val obj = array.optJSONObject(i) ?: continue
          val kind = AckKind.fromWire(obj.optString("kind")) ?: continue
          val deliveryId = obj.optString("deliveryId").takeIf { it.isNotBlank() } ?: continue
          val token = obj.optString("attributionToken").takeIf { it.isNotBlank() } ?: continue
          add(
            QueuedAck(
              deliveryId = deliveryId,
              attributionToken = token,
              kind = kind,
              actionId = obj.optString("actionId").takeIf { it.isNotBlank() },
              enqueuedAt = obj.optLong("enqueuedAt"),
              attempts = obj.optInt("attempts"),
              nextAttemptAt = obj.optLong("nextAttemptAt"),
            ),
          )
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun save(items: List<QueuedAck>) {
    val array = JSONArray()
    items.forEach { event ->
      val obj = JSONObject()
        .put("deliveryId", event.deliveryId)
        .put("attributionToken", event.attributionToken)
        .put("kind", event.kind.wire)
        .put("enqueuedAt", event.enqueuedAt)
        .put("attempts", event.attempts)
        .put("nextAttemptAt", event.nextAttemptAt)
      if (event.actionId != null) {
        obj.put("actionId", event.actionId)
      }
      array.put(obj)
    }
    store.set(storageKey(), array.toString())
  }

  private fun loadSent(): MutableMap<String, Long> {
    val raw = store.get(sentStorageKey()) ?: return mutableMapOf()
    return try {
      val array = JSONArray(raw)
      val out = mutableMapOf<String, Long>()
      for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val key = obj.optString("key").takeIf { it.isNotBlank() } ?: continue
        out[key] = obj.optLong("sentAt")
      }
      out
    } catch (_: Exception) {
      mutableMapOf()
    }
  }

  private fun saveSent(sent: Map<String, Long>) {
    val array = JSONArray()
    sent.forEach { (key, sentAt) ->
      array.put(JSONObject().put("key", key).put("sentAt", sentAt))
    }
    store.set(sentStorageKey(), array.toString())
  }

  private fun pruneSentLocked(now: Long) {
    val sent = loadSent().filterValues { now - it <= maxAgeMs }
    saveSent(sent)
  }

  private fun wasSentRecentlyLocked(event: QueuedAck, now: Long): Boolean {
    val sentAt = loadSent()[eventKey(event)] ?: return false
    return now - sentAt <= maxAgeMs
  }

  private fun markSentLocked(event: QueuedAck, now: Long) {
    val sent = loadSent()
    sent[eventKey(event)] = now
    saveSent(sent.filterValues { now - it <= maxAgeMs })
  }

  /** Dedupe key: Delivery id + kind (opened is one per Delivery, action id ignored). */
  private fun eventKey(event: QueuedAck): String = "${event.kind.wire}:${event.deliveryId}"

  private sealed class SendResult {
    data object SUCCESS : SendResult()
    data object DROP : SendResult()
    data class RETRY(val nextAttemptAt: Long?) : SendResult()
  }

  private companion object {
    const val DEFAULT_MAX_SIZE = 100
    const val DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    const val DEFAULT_MAX_FLUSH = 20

    // Process-wide: every AckQueue instance shares one store, so flushes must not overlap.
    val flushLock = Any()
    @Volatile
    var flushInFlight = false
    val flushExecutor: java.util.concurrent.Executor =
      java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "comraid-ack-flush").apply { isDaemon = true }
      }
  }
}
