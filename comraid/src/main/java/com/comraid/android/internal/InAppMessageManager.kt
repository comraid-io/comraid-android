package com.comraid.android.internal

import com.comraid.android.InAppButton
import com.comraid.android.InAppMessage
import com.comraid.android.InAppMessageHandle
import com.comraid.android.InAppMessageListener
import com.comraid.android.InAppMessagePresenter
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Draws a message with the SDK's default layouts. Returns false when there is no Activity to draw on. */
internal interface InAppDisplay {
  /** True when there is an Activity the message could be drawn on right now. */
  fun isAvailable(): Boolean

  fun show(message: InAppMessage, handle: InAppMessageHandle): Boolean
}

/**
 * Implemented by the SDK's message handle. A layout calls [interrupted] when the system tore the
 * message down (rotation, a finishing Activity) with no action by the user, so it can show again.
 */
internal interface InAppInterruptible {
  fun interrupted()
}

/** Storage key for the In-app cache: scoped to the backend and App ID so environments never share it. */
internal fun inAppStateKey(apiBaseUrl: String, appId: String): String =
  "state:${apiBaseUrl.trimEnd('/')}:${appId.trim()}"

/** Where impression, click and dismissal ACKs go (the durable [AckQueue] in production). */
internal interface InAppAckSink {
  fun delivered(deliveryId: String, token: String)

  fun opened(deliveryId: String, token: String, actionId: String?)

  fun dismissed(deliveryId: String, token: String)
}

/** Sends In-app outcomes through the durable [AckQueue]; [flush] decides when the network is touched. */
internal class AckQueueInAppSink(
  private val queue: AckQueue,
  private val clock: () -> Long,
  private val flush: () -> Unit,
) : InAppAckSink {
  override fun delivered(deliveryId: String, token: String) = enqueue(deliveryId, token, AckKind.DELIVERED, null)

  override fun opened(deliveryId: String, token: String, actionId: String?) =
    enqueue(deliveryId, token, AckKind.OPENED, actionId)

  override fun dismissed(deliveryId: String, token: String) = enqueue(deliveryId, token, AckKind.DISMISSED, null)

  private fun enqueue(deliveryId: String, token: String, kind: AckKind, actionId: String?) {
    val now = clock()
    queue.enqueue(
      QueuedAck(
        deliveryId = deliveryId,
        attributionToken = token,
        kind = kind,
        actionId = actionId?.takeIf { it.isNotBlank() },
        enqueuedAt = now,
        attempts = 0,
        nextAttemptAt = now,
      ),
    )
    flush()
  }
}

/**
 * Fetches, caches and shows In-app messages: at most one per app foreground, never one outside
 * its display window, none while paused, and only after the host can draw it (an Activity is
 * resumed) or a presenter takes it. The server decides who may see what; this class decides
 * when, and keeps a local record of what it already showed so a message cannot reappear before
 * the server has processed its impression.
 */
internal class InAppMessageManager(
  private val fetcher: () -> List<FetchedInAppMessage>,
  private val acks: InAppAckSink,
  private val store: JsonBlobStore,
  private val clock: () -> Long,
  private val runInBackground: (() -> Unit) -> Unit,
  private val runOnMain: (() -> Unit) -> Unit,
  private val display: InAppDisplay,
) {
  private val lock = Any()
  private var cache: List<FetchedInAppMessage> = emptyList()
  private val shownIds = LinkedHashSet<String>()
  private var shownThisForeground = false

  // True from the start of a foreground until its fetch has finished (success or failure): the
  // cache from an earlier run may be stale, so nothing is shown from it in the meantime.
  private var fetchPending = false

  // Bumped whenever local state is cleared, so a fetch that started before an opt-out or logout
  // cannot write its (now unwanted) answer back.
  private var generation = 0

  private val listeners = CopyOnWriteArrayList<InAppMessageListener>()
  private val triggers = ConcurrentHashMap<String, String>()

  @Volatile var paused: Boolean = false

  @Volatile var presenter: InAppMessagePresenter? = null

  init {
    load()
  }

  fun addListener(listener: InAppMessageListener) {
    listeners.addIfAbsent(listener)
  }

  fun removeListener(listener: InAppMessageListener) {
    listeners.remove(listener)
  }

  fun addTrigger(key: String, value: String) {
    triggers[key] = value
  }

  fun addTriggers(values: Map<String, String>) {
    triggers.putAll(values)
  }

  fun removeTrigger(key: String) {
    triggers.remove(key)
  }

  fun clearTriggers() {
    triggers.clear()
  }

  fun getTriggers(): Map<String, String> = triggers.toMap()

  /** The app came to the foreground: fetch, then show at most one message. */
  fun onForeground() {
    val startedAt: Int
    synchronized(lock) {
      shownThisForeground = false
      fetchPending = true
      startedAt = generation
    }
    runInBackground {
      val result = runCatching { fetcher() }
      synchronized(lock) {
        fetchPending = false
        if (generation == startedAt) {
          result.onSuccess { fetched ->
            cache = fetched
            persistLocked()
          }
        }
      }
      runOnMain { maybeShow() }
    }
  }

  /** An Activity is on screen: a message that was waiting for one can now be drawn. */
  fun onHostReady() {
    maybeShow()
  }

  /** Forget cached messages, e.g. after the Contact opted out of in-app messages. */
  fun clearLocalState() {
    synchronized(lock) {
      generation++
      cache = emptyList()
      persistLocked()
    }
  }

  /** Forget everything, including what was shown: a different user may now be on this install (login, logout). */
  fun reset() {
    synchronized(lock) {
      generation++
      cache = emptyList()
      shownIds.clear()
      shownThisForeground = false
      persistLocked()
    }
  }

  private fun maybeShow() {
    val currentPresenter = presenter
    // Nothing to draw on yet (no Activity): keep the message, announce nothing.
    if (currentPresenter == null && !display.isAvailable()) return
    val candidate = claimNext() ?: return
    val handle = Handle(candidate)
    notifyListeners { it.willDisplay(candidate.message) }
    if (currentPresenter != null) {
      try {
        currentPresenter.present(candidate.message, handle, getTriggers())
      } catch (_: Exception) {
        // Host code failed: the message was not taken, so it is offered again later.
        unclaim(candidate)
      }
      return
    }
    if (!display.show(candidate.message, handle)) {
      unclaim(candidate)
    }
  }

  /** Pick the oldest message that may show now and mark it shown, atomically. */
  private fun claimNext(): FetchedInAppMessage? = synchronized(lock) {
    if (paused || shownThisForeground || fetchPending) return null
    val now = clock()
    val next = cache.firstOrNull { it.message.id !in shownIds && inWindow(it.message, now) } ?: return null
    shownThisForeground = true
    shownIds += next.message.id
    while (shownIds.size > MAX_SHOWN_IDS) {
      shownIds.remove(shownIds.first())
    }
    persistLocked()
    next
  }

  private fun unclaim(message: FetchedInAppMessage) {
    synchronized(lock) {
      shownThisForeground = false
      shownIds.remove(message.message.id)
      persistLocked()
    }
  }

  private fun inWindow(message: InAppMessage, now: Long): Boolean =
    (message.startAtMillis == null || now >= message.startAtMillis) &&
      (message.endAtMillis == null || now < message.endAtMillis)

  private fun notifyListeners(block: (InAppMessageListener) -> Unit) {
    for (listener in listeners) {
      runCatching { block(listener) }
    }
  }

  private inner class Handle(private val fetched: FetchedInAppMessage) : InAppMessageHandle, InAppInterruptible {
    private val message = fetched.message
    private var impressionDone = false
    private var dismissDone = false
    private var clickedDone = false

    override fun impression() {
      synchronized(this) {
        if (impressionDone) return
        impressionDone = true
      }
      ack { id, token -> acks.delivered(id, token) }
      notifyListeners { it.didDisplay(message) }
    }

    override fun clicked(buttonId: String) {
      val button: InAppButton = message.buttons.firstOrNull { it.id == buttonId } ?: return
      synchronized(this) { clickedDone = true }
      ack { id, token -> acks.opened(id, token, buttonId) }
      notifyListeners { it.didClick(message, button) }
    }

    override fun dismissed() {
      val afterClick: Boolean
      synchronized(this) {
        if (dismissDone) return
        dismissDone = true
        afterClick = clickedDone
      }
      notifyListeners { it.willDismiss(message) }
      // Closing after a button tap finishes the lifecycle but is not a dismissal: the click is the outcome.
      if (!afterClick) ack { id, token -> acks.dismissed(id, token) }
      notifyListeners { it.didDismiss(message) }
    }

    override fun interrupted() {
      synchronized(this) {
        if (dismissDone) return
      }
      unclaim(fetched)
    }

    /** A Test send has no Delivery, so it never reaches the ACK queue. */
    private fun ack(send: (String, String) -> Unit) {
      if (message.isTest) return
      val id = fetched.deliveryId ?: return
      val token = fetched.attributionToken ?: return
      runCatching { send(id, token) }
    }
  }

  private fun load() {
    val raw = store.read() ?: return
    runCatching {
      val json = JSONObject(raw)
      cache = parseCachedInAppMessages(json.optJSONArray("messages"))
      val shown = json.optJSONArray("shown")
      if (shown != null) {
        for (i in 0 until shown.length()) shownIds += shown.optString(i)
      }
    }
  }

  private fun persistLocked() {
    val messages = JSONArray()
    for (item in cache) messages.put(item.toCacheJson())
    val shown = JSONArray()
    for (id in shownIds) shown.put(id)
    store.write(JSONObject().put("messages", messages).put("shown", shown).toString())
  }

  private companion object {
    const val MAX_SHOWN_IDS = 200
  }
}
