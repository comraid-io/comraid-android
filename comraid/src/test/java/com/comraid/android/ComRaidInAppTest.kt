package com.comraid.android

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.comraid.android.internal.HttpRequest
import com.comraid.android.internal.HttpResponse
import com.comraid.android.internal.HttpTransport
import com.comraid.android.internal.InMemorySubscriptionStore
import com.comraid.android.internal.TokenProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Seam: the public In-app message API on [ComRaid] and how it is wired to foregrounds, ACKs and opt-out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComRaidInAppTest {
  private class FakeServer : HttpTransport {
    val requests: MutableList<HttpRequest> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var inAppBody: String = EMPTY

    override fun execute(request: HttpRequest): HttpResponse {
      requests += request
      return when {
        request.method == "GET" && request.path.endsWith("/in-app-messages") -> HttpResponse(200, inAppBody)
        request.method == "POST" && request.path == "/mobile/subscriptions" ->
          HttpResponse(
            201,
            """{"subscription_id":"sub-2","subscription_credential":"cred-2","channel":"push","anonymous":true,"platform":"android","provider":"fcm"}""",
          )
        else -> HttpResponse(200, "{}")
      }
    }

    fun posts(suffix: String) = requests.filter { it.method == "POST" && it.path.endsWith(suffix) }

    fun fetches() = requests.count { it.method == "GET" && it.path.endsWith("/in-app-messages") }

    companion object {
      const val EMPTY = """{"messages":[]}"""
    }
  }

  private lateinit var server: FakeServer
  private lateinit var store: InMemorySubscriptionStore

  private fun message(id: String, buttons: String = """[{"id":"open","label":"Open","deep_link":"myapp://x"}]""") =
    """{"id":"$id","delivery_id":"d-$id","attribution_token":"t-$id","layout":"modal","title":"Title $id","body":"Body $id","buttons":$buttons}"""

  private fun serve(vararg messages: String) {
    server.inAppBody = """{"messages":[${messages.joinToString(",")}]}"""
  }

  @Before
  fun setUp() {
    ComRaid.resetForTests()
    server = FakeServer()
    store = InMemorySubscriptionStore().apply { save("sub-1", "cred-1") }
  }

  private fun configure() {
    ComRaid.configureForTests(
      configuration = ComRaidConfiguration(appId = "cr_app_test", smallIconResourceId = 1),
      transport = server,
      tokenProvider = TokenProvider { "fcm" },
      subscriptionStore = store,
    )
  }

  private class Capture : InAppMessagePresenter {
    val messages = mutableListOf<InAppMessage>()
    val handles = mutableListOf<InAppMessageHandle>()
    var triggers: Map<String, String> = emptyMap()
    override fun present(message: InAppMessage, handle: InAppMessageHandle, triggers: Map<String, String>) {
      messages += message
      handles += handle
      this.triggers = triggers
    }
  }

  @Test
  fun every_foreground_fetches_even_when_the_session_is_debounced() {
    serve(message("m1"))
    configure()
    val capture = Capture()
    ComRaid.setInAppMessagePresenter(capture)

    ComRaid.handleAppForegroundForTests()
    ComRaid.handleAppForegroundForTests()

    assertEquals(2, server.fetches())
    assertEquals("the session debounce must not apply to in-app fetches", 1, server.posts("/session").size)
    assertEquals(listOf("m1"), capture.messages.map { it.id })
  }

  @Test
  fun nothing_is_fetched_without_a_registered_subscription() {
    store.clear()
    serve(message("m1"))
    configure()

    ComRaid.handleAppForegroundForTests()

    assertEquals(0, server.fetches())
  }

  @Test
  fun outcomes_reach_the_server_through_the_ack_queue() {
    serve(message("m1"))
    configure()
    val capture = Capture()
    ComRaid.setInAppMessagePresenter(capture)
    ComRaid.handleAppForegroundForTests()

    val handle = capture.handles.single()
    handle.impression()
    handle.clicked("open")
    ComRaid.flushAcksForTests()

    val delivered = server.posts("/deliveries/d-m1/delivered").single()
    assertEquals("t-m1", JSONObject(delivered.body!!).getString("attribution_token"))
    val opened = server.posts("/deliveries/d-m1/opened").single()
    assertEquals("open", JSONObject(opened.body!!).getString("action_id"))
    assertEquals(0, server.posts("/deliveries/d-m1/dismissed").size)
  }

  @Test
  fun a_dismissal_reaches_the_server_through_the_ack_queue() {
    serve(message("m1"))
    configure()
    val capture = Capture()
    ComRaid.setInAppMessagePresenter(capture)
    ComRaid.handleAppForegroundForTests()

    capture.handles.single().impression()
    capture.handles.single().dismissed()
    ComRaid.flushAcksForTests()

    assertEquals(1, server.posts("/deliveries/d-m1/dismissed").size)
  }

  @Test
  fun logout_drops_the_previous_users_cached_messages_and_what_they_saw() {
    serve(message("m1"), message("m2"))
    configure()
    val capture = Capture()
    ComRaid.setInAppMessagePresenter(capture)
    ComRaid.handleAppForegroundForTests() // shows m1, caches m2

    ComRaid.logout() // the next user gets a fresh Subscription
    serve(message("m1")) // the server's answer for the new Subscription
    ComRaid.handleAppForegroundForTests()

    assertEquals("m1 shows again for the new user and the old user's m2 never does", listOf("m1", "m1"), capture.messages.map { it.id })
  }

  @Test
  fun login_drops_cached_messages_so_the_server_decides_again() {
    serve(message("m1"), message("m2"))
    configure()
    val capture = Capture()
    ComRaid.setInAppMessagePresenter(capture)
    ComRaid.handleAppForegroundForTests() // shows m1, caches m2

    ComRaid.login("user-42")
    serve(message("m3")) // the Contact is now a known user with a different audience
    ComRaid.handleAppForegroundForTests()

    assertEquals(listOf("m1", "m3"), capture.messages.map { it.id })
  }

  @Test
  fun a_paused_sdk_shows_nothing_until_resumed() {
    serve(message("m1"))
    configure()
    val capture = Capture()
    ComRaid.setInAppMessagePresenter(capture)
    ComRaid.setInAppMessagesPaused(true)
    assertTrue(ComRaid.isInAppMessagesPaused())

    ComRaid.handleAppForegroundForTests()
    assertTrue(capture.messages.isEmpty())

    ComRaid.setInAppMessagesPaused(false)
    assertFalse(ComRaid.isInAppMessagesPaused())
    val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().get()
    ComRaid.handleActivityResumedForTests(activity)
    assertEquals(listOf("m1"), capture.messages.map { it.id })
  }

  @Test
  fun triggers_are_passed_to_the_presenter() {
    serve(message("m1"))
    configure()
    val capture = Capture()
    ComRaid.setInAppMessagePresenter(capture)
    ComRaid.addTrigger("screen", "checkout")
    ComRaid.addTriggers(mapOf("plan" to "pro", "tmp" to "x"))
    ComRaid.removeTrigger("tmp")

    ComRaid.handleAppForegroundForTests()

    assertEquals(mapOf("screen" to "checkout", "plan" to "pro"), capture.triggers)
    assertEquals(mapOf("screen" to "checkout", "plan" to "pro"), ComRaid.getTriggers())
    ComRaid.clearTriggers()
    assertTrue(ComRaid.getTriggers().isEmpty())
  }

  @Test
  fun registrations_made_before_configure_still_apply() {
    val capture = Capture()
    ComRaid.setInAppMessagePresenter(capture)
    ComRaid.addTrigger("early", "yes")
    serve(message("m1"))

    configure()
    ComRaid.handleAppForegroundForTests()

    assertEquals(listOf("m1"), capture.messages.map { it.id })
    assertEquals(mapOf("early" to "yes"), capture.triggers)
  }

  @Test
  fun a_registered_listener_observes_the_message_lifecycle_until_removed() {
    serve(message("m1"), message("m2"))
    configure()
    val capture = Capture()
    ComRaid.setInAppMessagePresenter(capture)
    val events = mutableListOf<String>()
    val listener = object : InAppMessageListener {
      override fun didDisplay(message: InAppMessage) {
        events += "didDisplay:${message.id}"
      }

      override fun didClick(message: InAppMessage, button: InAppButton) {
        events += "didClick:${message.id}:${button.deepLink}"
      }
    }
    ComRaid.addInAppMessageListener(listener)

    ComRaid.handleAppForegroundForTests()
    capture.handles[0].impression()
    capture.handles[0].clicked("open")
    assertEquals(listOf("didDisplay:m1", "didClick:m1:myapp://x"), events)

    ComRaid.removeInAppMessageListener(listener)
    ComRaid.handleAppForegroundForTests()
    capture.handles[1].impression()
    assertEquals(2, events.size)
  }

  @Test
  fun the_in_app_opt_out_posts_its_channel_and_stops_cached_messages_showing() {
    serve(message("m1"), message("m2"))
    configure()
    val capture = Capture()
    ComRaid.setInAppMessagePresenter(capture)
    ComRaid.handleAppForegroundForTests() // shows m1, caches m2

    ComRaid.optOutInApp()
    server.inAppBody = FakeServer.EMPTY // the server returns nothing for an opted-out Contact
    ComRaid.handleAppForegroundForTests()

    val optOut = server.posts("/opt-out").single()
    assertEquals("in_app", JSONObject(optOut.body!!).getString("channel"))
    assertEquals(listOf("m1"), capture.messages.map { it.id })

    ComRaid.optInInApp()
    assertEquals("in_app", JSONObject(server.posts("/opt-in").single().body!!).getString("channel"))
  }

  @Test
  fun the_default_layout_is_drawn_on_the_resumed_activity_and_reports_outcomes() {
    serve(message("m1"))
    configure()
    val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
    ComRaid.handleActivityResumedForTests(activity)

    ComRaid.handleAppForegroundForTests()

    val dialog = ShadowDialog.getLatestDialog()
    assertNotNull("a dialog should be showing", dialog)
    assertTrue(dialog!!.isShowing)
    val labels = texts(dialog.window!!.decorView)
    assertTrue(labels.containsAll(listOf("Title m1", "Body m1", "Open")))

    texts(dialog.window!!.decorView) // tap the button
    allTextViews(dialog.window!!.decorView).first { it.text.toString() == "Open" }.performClick()
    ComRaid.flushAcksForTests()

    assertEquals(1, server.posts("/deliveries/d-m1/delivered").size)
    assertEquals(1, server.posts("/deliveries/d-m1/opened").size)
    assertEquals("the SDK must not launch the deep link or dismiss on a click", 0, server.posts("/deliveries/d-m1/dismissed").size)
  }

  @Test
  fun a_message_waits_until_an_activity_is_on_screen() {
    serve(message("m1"))
    configure()

    ComRaid.handleAppForegroundForTests()
    assertEquals(null, ShadowDialog.getLatestDialog())

    val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible().get()
    ComRaid.handleActivityResumedForTests(activity)
    assertNotNull(ShadowDialog.getLatestDialog())

    ComRaid.handleActivityPausedForTests(activity)
  }

  private fun allTextViews(view: View): List<TextView> {
    val found = mutableListOf<TextView>()
    fun walk(v: View) {
      if (v is TextView) found += v
      if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(view)
    return found
  }

  private fun texts(view: View): List<String> = allTextViews(view).map { it.text.toString() }
}
