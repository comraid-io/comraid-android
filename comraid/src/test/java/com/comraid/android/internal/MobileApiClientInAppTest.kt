package com.comraid.android.internal

import com.comraid.android.ComRaidException
import com.comraid.android.InAppLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Seam: [MobileApiClient] fetching In-app messages (GET /in-app-messages) and the in_app opt-out. */
class MobileApiClientInAppTest {
  private class Recorder(private val response: HttpResponse) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override fun execute(request: HttpRequest): HttpResponse {
      requests += request
      return response
    }
  }

  private val body = """
    {"messages":[
      {"id":"m1","delivery_id":"d1","attribution_token":"tok1","layout":"modal","title":"Welcome","body":"Hi there",
       "image_url":"https://cdn.example.com/a.png",
       "buttons":[{"id":"open","label":"Open","deep_link":"myapp://new"},{"id":"later","label":"Later"}],
       "start_at":"2026-12-01T03:30:00.000Z","end_at":"2026-12-31T13:00:00Z"},
      {"id":"m2","test":true,"layout":"banner_top","title":"Test","body":"Only a test","buttons":[]},
      {"id":"m3","delivery_id":"d3","attribution_token":"tok3","layout":"carousel","title":"Unknown layout","body":"x","buttons":[]},
      {"layout":"modal","title":"No id","body":"x","buttons":[]}
    ]}
  """.trimIndent()

  @Test
  fun listInAppMessages_sends_app_id_and_credential_as_a_GET() {
    val recorder = Recorder(HttpResponse(200, body))
    MobileApiClient("cr_app_test", recorder).listInAppMessages("sub-1", "cred-1")

    val request = recorder.requests.single()
    assertEquals("GET", request.method)
    assertEquals("/mobile/subscriptions/sub-1/in-app-messages", request.path)
    assertEquals("cr_app_test", request.headers["X-ComRaid-App-Id"])
    assertEquals("cred-1", request.headers["X-ComRaid-Subscription-Credential"])
    assertNull(request.body)
  }

  @Test
  fun listInAppMessages_parses_a_real_message_with_its_ack_credentials() {
    val messages = MobileApiClient("cr_app_test", Recorder(HttpResponse(200, body)))
      .listInAppMessages("sub-1", "cred-1")

    val first = messages.first { it.message.id == "m1" }
    assertEquals("d1", first.deliveryId)
    assertEquals("tok1", first.attributionToken)
    assertEquals(InAppLayout.MODAL, first.message.layout)
    assertEquals("Welcome", first.message.title)
    assertEquals("Hi there", first.message.body)
    assertEquals("https://cdn.example.com/a.png", first.message.imageUrl)
    assertFalse(first.message.isTest)
    assertEquals(listOf("open", "later"), first.message.buttons.map { it.id })
    assertEquals("myapp://new", first.message.buttons[0].deepLink)
    assertNull(first.message.buttons[1].deepLink)
    assertEquals(1_796_095_800_000L, first.message.startAtMillis) // 2026-12-01T03:30:00Z
    assertEquals(1_798_722_000_000L, first.message.endAtMillis) // 2026-12-31T13:00:00Z
  }

  @Test
  fun listInAppMessages_marks_a_Test_send_and_leaves_out_its_ack_credentials() {
    val messages = MobileApiClient("cr_app_test", Recorder(HttpResponse(200, body)))
      .listInAppMessages("sub-1", "cred-1")

    val test = messages.first { it.message.id == "m2" }
    assertTrue(test.message.isTest)
    assertNull(test.deliveryId)
    assertNull(test.attributionToken)
    assertEquals(InAppLayout.BANNER_TOP, test.message.layout)
    assertNull(test.message.startAtMillis)
    assertNull(test.message.endAtMillis)
  }

  @Test
  fun listInAppMessages_skips_messages_it_cannot_draw() {
    val ids = MobileApiClient("cr_app_test", Recorder(HttpResponse(200, body)))
      .listInAppMessages("sub-1", "cred-1")
      .map { it.message.id }

    assertEquals(listOf("m1", "m2"), ids) // unknown layout and missing id are dropped
  }

  @Test
  fun listInAppMessages_throws_the_server_message_on_failure() {
    val api = MobileApiClient(
      "cr_app_test",
      Recorder(HttpResponse(401, """{"error":{"code":"unauthorized","message":"Invalid subscription credential"}}""")),
    )
    try {
      api.listInAppMessages("sub-1", "bad")
      fail("expected ApiError")
    } catch (e: ComRaidException.ApiError) {
      assertEquals("Invalid subscription credential", e.message)
    }
  }

  @Test
  fun optOut_for_the_in_app_channel_posts_that_channel() {
    val recorder = Recorder(HttpResponse(200, "{}"))
    MobileApiClient("cr_app_test", recorder).optOut("sub-1", "cred-1", channel = "in_app")

    val request = recorder.requests.single()
    assertEquals("/mobile/subscriptions/sub-1/opt-out", request.path)
    assertEquals("in_app", JSONObject(request.body!!).getString("channel"))
  }
}
