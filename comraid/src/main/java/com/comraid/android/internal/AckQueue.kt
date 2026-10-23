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
 */
internal class AckQueue(
  apiBaseUrl: String,
  private val store: AckQueueStore,
  private val transport: HttpTransport,
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val maxSize: Int = DEFAULT_MAX_SIZE,
  private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
  private val maxFlush: Int = DEFAULT_MAX_FLUSH,
) {
  private val baseUrl = apiBaseUrl.trimEnd('/')

  fun storageKey(): String = "comraid:$baseUrl:ack-queue"

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
    val now = clock()
    var items = load().filter { now - it.enqueuedAt <= maxAgeMs }
    val key = eventKey(event)
    items = items.filter { eventKey(it) != key }
    items = items + event
    while (items.size > maxSize) {
      items = items.drop(1)
    }
    save(items)
  }

  fun flush() {
    val now = clock()
    var items = load().filter { now - it.enqueuedAt <= maxAgeMs }
    val ready = items.filter { it.nextAttemptAt <= now }.take(maxFlush)
    if (ready.isEmpty()) {
      save(items)
      return
    }

    val readyKeys = ready.map { eventKey(it) }.toSet()
    var remaining = items.filter { eventKey(it) !in readyKeys }

    for (event in ready) {
      when (val result = send(event)) {
        SendResult.SUCCESS, SendResult.DROP -> Unit
        is SendResult.RETRY -> {
          val attempts = event.attempts + 1
          val nextAt = result.nextAttemptAt
            ?: (clock() + min(60_000L, (1000.0 * 2.0.pow(min(attempts, 5))).toLong()))
          remaining = remaining + event.copy(attempts = attempts, nextAttemptAt = nextAt)
        }
      }
    }

    while (remaining.size > maxSize) {
      remaining = remaining.drop(1)
    }
    save(remaining)
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

  private fun eventKey(event: QueuedAck): String =
    "${event.kind.wire}:${event.deliveryId}:${event.actionId.orEmpty()}"

  private sealed class SendResult {
    data object SUCCESS : SendResult()
    data object DROP : SendResult()
    data class RETRY(val nextAttemptAt: Long?) : SendResult()
  }

  private companion object {
    const val DEFAULT_MAX_SIZE = 100
    const val DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    const val DEFAULT_MAX_FLUSH = 20
  }
}
