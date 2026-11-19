package com.comraid.android.internal

import com.comraid.android.InAppButton
import com.comraid.android.InAppLayout
import com.comraid.android.InAppMessage
import com.comraid.android.InAppMessageHandle
import com.comraid.android.InAppMessageListener
import com.comraid.android.InAppMessagePresenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Seam: [InAppMessageManager], the core of In-app messages on Android. It is driven with fake
 * fetch, display, ACK sink, clock and store; nothing here needs an emulator.
 */
class InAppMessageManagerTest {
  private class FakeDisplay : InAppDisplay {
    var available = true
    val shown = mutableListOf<InAppMessage>()
    var lastHandle: InAppMessageHandle? = null
    override fun isAvailable(): Boolean = available

    override fun show(message: InAppMessage, handle: InAppMessageHandle): Boolean {
      if (!available) return false
      shown += message
      lastHandle = handle
      return true
    }
  }

  private class RecordingAcks : InAppAckSink {
    val events = mutableListOf<String>()
    override fun delivered(deliveryId: String, token: String) {
      events += "delivered:$deliveryId:$token"
    }

    override fun opened(deliveryId: String, token: String, actionId: String?) {
      events += "opened:$deliveryId:$token:$actionId"
    }

    override fun dismissed(deliveryId: String, token: String) {
      events += "dismissed:$deliveryId:$token"
    }
  }

  private class RecordingListener : InAppMessageListener {
    val events = mutableListOf<String>()
    override fun willDisplay(message: InAppMessage) {
      events += "willDisplay:${message.id}"
    }

    override fun didDisplay(message: InAppMessage) {
      events += "didDisplay:${message.id}"
    }

    override fun willDismiss(message: InAppMessage) {
      events += "willDismiss:${message.id}"
    }

    override fun didDismiss(message: InAppMessage) {
      events += "didDismiss:${message.id}"
    }

    override fun didClick(message: InAppMessage, button: InAppButton) {
      events += "didClick:${message.id}:${button.id}:${button.deepLink}"
    }
  }

  private var now = 1_000_000L
  private lateinit var display: FakeDisplay
  private lateinit var acks: RecordingAcks
  private lateinit var store: InMemoryJsonBlobStore
  private var fetched: List<FetchedInAppMessage> = emptyList()
  private var fetchFails = false
  private var fetchCalls = 0

  @Before
  fun setUp() {
    display = FakeDisplay()
    acks = RecordingAcks()
    store = InMemoryJsonBlobStore()
    fetched = emptyList()
    fetchFails = false
    fetchCalls = 0
  }

  private fun manager(blobStore: JsonBlobStore = store) = InAppMessageManager(
    fetcher = {
      fetchCalls++
      if (fetchFails) throw RuntimeException("offline")
      fetched
    },
    acks = acks,
    store = blobStore,
    clock = { now },
    runInBackground = { it() },
    runOnMain = { it() },
    display = display,
  )

  private fun msg(
    id: String,
    start: Long? = null,
    end: Long? = null,
    test: Boolean = false,
    buttons: List<InAppButton> = listOf(InAppButton("open", "Open", "myapp://x")),
  ) = FetchedInAppMessage(
    message = InAppMessage(
      id = id,
      layout = InAppLayout.MODAL,
      title = "Title $id",
      body = "Body $id",
      buttons = buttons,
      startAtMillis = start,
      endAtMillis = end,
      isTest = test,
    ),
    deliveryId = if (test) null else "delivery-$id",
    attributionToken = if (test) null else "token-$id",
  )

  @Test
  fun foreground_fetches_and_shows_the_oldest_message_once_per_foreground() {
    fetched = listOf(msg("a"), msg("b"))
    val manager = manager()

    manager.onForeground()
    assertEquals(listOf("a"), display.shown.map { it.id })

    // A second message is not shown in the same foreground, even when the host is ready again.
    manager.onHostReady()
    assertEquals(listOf("a"), display.shown.map { it.id })

    // The next foreground shows the next message, never the one already shown.
    manager.onForeground()
    assertEquals(listOf("a", "b"), display.shown.map { it.id })
    manager.onForeground()
    assertEquals(listOf("a", "b"), display.shown.map { it.id })
  }

  @Test
  fun cached_messages_are_shown_when_the_fetch_fails_and_survive_a_restart() {
    fetched = listOf(msg("a"))
    manager().onForeground() // fetch + cache + show "a"
    display.shown.clear()

    fetched = listOf(msg("a"), msg("b"))
    manager().onForeground() // a fresh manager over the same store: "a" already shown, "b" is new
    assertEquals(listOf("b"), display.shown.map { it.id })

    display.shown.clear()
    fetchFails = true
    val restarted = manager()
    restarted.onForeground() // offline: the cache still has nothing new to show
    assertTrue(display.shown.isEmpty())
  }

  @Test
  fun a_failed_fetch_still_shows_what_was_cached_earlier() {
    fetched = listOf(msg("a"), msg("b"))
    val first = manager()
    first.onForeground() // shows a, caches a and b
    display.shown.clear()

    fetchFails = true
    first.onForeground() // offline, but "b" is cached
    assertEquals(listOf("b"), display.shown.map { it.id })
  }

  @Test
  fun messages_outside_their_display_window_are_not_shown() {
    fetched = listOf(
      msg("future", start = now + 60_000),
      msg("expired", end = now - 60_000),
      msg("open", start = now - 60_000, end = now + 60_000),
    )
    manager().onForeground()

    assertEquals(listOf("open"), display.shown.map { it.id })
  }

  @Test
  fun a_paused_manager_shows_nothing_until_resumed() {
    fetched = listOf(msg("a"))
    val manager = manager()
    manager.paused = true

    manager.onForeground()
    assertTrue(display.shown.isEmpty())

    manager.paused = false
    manager.onHostReady()
    assertEquals(listOf("a"), display.shown.map { it.id })
  }

  @Test
  fun a_message_waits_for_a_host_activity_instead_of_being_lost() {
    fetched = listOf(msg("a"))
    display.available = false
    val manager = manager()

    manager.onForeground()
    assertTrue(display.shown.isEmpty())

    display.available = true
    manager.onHostReady()
    assertEquals(listOf("a"), display.shown.map { it.id })
  }

  @Test
  fun a_presenter_receives_the_message_handle_and_triggers_instead_of_the_default_layout() {
    fetched = listOf(msg("a"))
    val manager = manager()
    manager.addTrigger("screen", "checkout")
    var received: InAppMessage? = null
    var receivedTriggers: Map<String, String>? = null
    manager.presenter = InAppMessagePresenter { message, _, triggers ->
      received = message
      receivedTriggers = triggers
    }

    manager.onForeground()

    assertEquals("a", received?.id)
    assertEquals(mapOf("screen" to "checkout"), receivedTriggers)
    assertTrue("the default layout must not draw when a presenter is set", display.shown.isEmpty())
  }

  @Test
  fun the_handle_reports_impression_click_and_dismissal_through_the_ack_queue() {
    fetched = listOf(msg("a"))
    val manager = manager()
    var handle: InAppMessageHandle? = null
    manager.presenter = InAppMessagePresenter { _, h, _ -> handle = h }
    manager.onForeground()

    handle!!.impression()
    handle!!.impression() // a repeat is ignored
    handle!!.clicked("open")

    assertEquals(listOf("delivered:delivery-a:token-a", "opened:delivery-a:token-a:open"), acks.events)
  }

  @Test
  fun a_dismissal_without_a_click_is_acknowledged_once() {
    fetched = listOf(msg("a"))
    val manager = manager()
    var handle: InAppMessageHandle? = null
    manager.presenter = InAppMessagePresenter { _, h, _ -> handle = h }
    manager.onForeground()

    handle!!.impression()
    handle!!.dismissed()
    handle!!.dismissed()

    assertEquals(listOf("delivered:delivery-a:token-a", "dismissed:delivery-a:token-a"), acks.events)
  }

  @Test
  fun closing_after_a_click_completes_the_lifecycle_but_is_not_a_dismissal_ack() {
    fetched = listOf(msg("a"))
    val manager = manager()
    val listener = RecordingListener()
    manager.addListener(listener)
    var handle: InAppMessageHandle? = null
    manager.presenter = InAppMessagePresenter { _, h, _ -> handle = h }
    manager.onForeground()

    handle!!.impression()
    handle!!.clicked("open")
    handle!!.dismissed() // the layout closing after the tap

    assertEquals(listOf("delivered:delivery-a:token-a", "opened:delivery-a:token-a:open"), acks.events)
    assertTrue(listener.events.containsAll(listOf("willDismiss:a", "didDismiss:a")))
  }

  @Test
  fun a_click_on_an_unknown_button_is_ignored() {
    fetched = listOf(msg("a"))
    val manager = manager()
    var handle: InAppMessageHandle? = null
    manager.presenter = InAppMessagePresenter { _, h, _ -> handle = h }
    manager.onForeground()

    handle!!.clicked("not-a-button")

    assertTrue(acks.events.isEmpty())
  }

  @Test
  fun a_Test_send_is_shown_but_never_acknowledged() {
    fetched = listOf(msg("t", test = true))
    val manager = manager()
    val listener = RecordingListener()
    manager.addListener(listener)
    manager.onForeground()

    val handle = display.lastHandle!!
    handle.impression()
    handle.clicked("open")
    handle.dismissed()

    assertEquals(listOf("t"), display.shown.map { it.id })
    assertTrue("a Test send makes no ACKs", acks.events.isEmpty())
    assertTrue(listener.events.contains("didDisplay:t"))
  }

  @Test
  fun listeners_see_the_display_click_and_dismiss_lifecycle_in_order() {
    fetched = listOf(msg("a"))
    val manager = manager()
    val listener = RecordingListener()
    manager.addListener(listener)
    manager.onForeground()

    val handle = display.lastHandle!!
    handle.impression()
    handle.clicked("open")
    handle.dismissed()

    assertEquals(
      listOf(
        "willDisplay:a",
        "didDisplay:a",
        "didClick:a:open:myapp://x",
        "willDismiss:a",
        "didDismiss:a",
      ),
      listener.events,
    )

    manager.removeListener(listener)
    manager.onForeground()
    assertEquals(5, listener.events.size)
  }

  @Test
  fun triggers_can_be_added_removed_and_cleared() {
    val manager = manager()
    manager.addTrigger("a", "1")
    manager.addTriggers(mapOf("b" to "2", "c" to "3"))
    assertEquals(mapOf("a" to "1", "b" to "2", "c" to "3"), manager.getTriggers())

    manager.removeTrigger("b")
    assertEquals(mapOf("a" to "1", "c" to "3"), manager.getTriggers())

    manager.clearTriggers()
    assertTrue(manager.getTriggers().isEmpty())
  }

  @Test
  fun clearing_local_state_drops_the_cache_so_nothing_shows_after_an_opt_out() {
    fetched = listOf(msg("a"), msg("b"))
    val manager = manager()
    manager.onForeground() // shows a, caches b
    display.shown.clear()

    manager.clearLocalState()
    fetched = emptyList() // the server returns nothing for an opted-out Contact
    manager.onForeground()

    assertTrue(display.shown.isEmpty())
    assertNull(store.read()?.takeIf { it.contains("\"b\"") })
  }

  @Test
  fun a_message_shown_in_the_foreground_is_not_offered_by_the_cache_again() {
    fetched = listOf(msg("a"))
    val manager = manager()
    manager.onForeground()
    assertFalse(display.shown.isEmpty())
    display.shown.clear()

    // The server still lists "a" (its impression ACK has not been processed yet).
    manager.onForeground()
    assertTrue(display.shown.isEmpty())
  }

  // ---- background fetch, interleaving and failure handling ----

  private class Deferred {
    val queue = mutableListOf<() -> Unit>()
    fun run() {
      while (queue.isNotEmpty()) queue.removeAt(0)()
    }
  }

  private fun deferredManager(deferred: Deferred, blobStore: JsonBlobStore = store) = InAppMessageManager(
    fetcher = {
      fetchCalls++
      if (fetchFails) throw RuntimeException("offline")
      fetched
    },
    acks = acks,
    store = blobStore,
    clock = { now },
    runInBackground = { deferred.queue += it },
    runOnMain = { it() },
    display = display,
  )

  @Test
  fun a_stale_cached_message_is_not_shown_before_this_foregrounds_fetch_returns() {
    // An earlier run left "stale" in the cache (and has not shown it).
    store.write(
      org.json.JSONObject()
        .put("messages", org.json.JSONArray().put(msg("stale").toCacheJson()))
        .put("shown", org.json.JSONArray())
        .toString(),
    )
    display.shown.clear()

    val deferred = Deferred()
    val manager = deferredManager(deferred)
    manager.onForeground() // fetch is in flight
    manager.onHostReady() // an Activity resumes meanwhile
    assertTrue("the stale cache must wait for the fetch", display.shown.isEmpty())

    fetched = listOf(msg("fresh")) // the server no longer lists "stale"
    deferred.run()

    assertEquals(listOf("fresh"), display.shown.map { it.id })
  }

  @Test
  fun a_fetch_in_flight_cannot_bring_back_messages_after_an_opt_out() {
    val deferred = Deferred()
    val manager = deferredManager(deferred)
    fetched = listOf(msg("a"))
    manager.onForeground()

    manager.clearLocalState() // the Contact opts out while the fetch runs
    deferred.run()

    assertTrue("a pre-opt-out response must be dropped", display.shown.isEmpty())
  }

  @Test
  fun reset_forgets_the_cache_and_what_was_shown() {
    fetched = listOf(msg("a"))
    val manager = manager()
    manager.onForeground()
    assertEquals(listOf("a"), display.shown.map { it.id })
    display.shown.clear()

    manager.reset() // e.g. logout: a different user may now be on this install
    manager.onForeground()

    assertEquals(listOf("a"), display.shown.map { it.id })
  }

  @Test
  fun a_presenter_that_throws_does_not_crash_or_consume_the_message() {
    fetched = listOf(msg("a"))
    val manager = manager()
    var calls = 0
    manager.presenter = InAppMessagePresenter { _, _, _ ->
      calls++
      throw IllegalStateException("host bug")
    }

    manager.onForeground() // must not throw
    manager.onForeground()

    assertEquals("the message is offered again because the presenter never took it", 2, calls)
  }

  @Test
  fun listeners_hear_willDisplay_only_when_the_message_can_actually_be_drawn() {
    fetched = listOf(msg("a"))
    display.available = false
    val manager = manager()
    val listener = RecordingListener()
    manager.addListener(listener)

    manager.onForeground()
    manager.onHostReady()
    assertTrue("no Activity: nothing was displayed, so nothing is announced", listener.events.isEmpty())

    display.available = true
    manager.onHostReady()
    assertEquals(listOf("willDisplay:a"), listener.events)
  }

  @Test
  fun a_message_torn_down_by_the_system_is_shown_again() {
    fetched = listOf(msg("a"))
    val manager = manager()
    manager.onForeground()
    assertEquals(listOf("a"), display.shown.map { it.id })

    (display.lastHandle as InAppInterruptible).interrupted() // e.g. a rotation destroyed the Activity
    manager.onHostReady() // the new Activity resumes

    assertEquals(listOf("a", "a"), display.shown.map { it.id })
  }

  @Test
  fun the_cache_key_is_scoped_to_the_app_and_backend() {
    val production = inAppStateKey("https://api.comraid.io/", "cr_app_prod")
    assertEquals(production, inAppStateKey("https://api.comraid.io", "cr_app_prod"))
    assertTrue(production != inAppStateKey("https://api.comraid.io", "cr_app_other"))
    assertTrue(production != inAppStateKey("https://staging.comraid.io", "cr_app_prod"))
  }
}
