package com.comraid.android

import com.comraid.android.internal.HttpRequest
import com.comraid.android.internal.HttpResponse
import com.comraid.android.internal.HttpTransport
import com.comraid.android.internal.InMemorySubscriptionStore
import com.comraid.android.internal.TokenProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComRaidIdentityTest {
  private lateinit var transport: RecordingTransport
  private lateinit var store: InMemorySubscriptionStore
  private lateinit var tokens: FakeTokenProvider

  @Before
  fun setUp() {
    ComRaid.resetForTests()
    transport = RecordingTransport()
    store = InMemorySubscriptionStore()
    tokens = FakeTokenProvider("fcm-token-1")
    ComRaid.configureForTests(
      configuration = ComRaidConfiguration(
        appId = "cr_app_test",
        smallIconResourceId = 1,
      ),
      transport = transport,
      tokenProvider = tokens,
      subscriptionStore = store,
    )
  }

  @Test
  fun login_sends_external_user_id_on_wire() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")

    transport.nextResponse = HttpResponse(
      statusCode = 200,
      body = """{"subscription_id":"sub-1","contact_id":"c-1","external_user_id":"user_123","anonymous":false,"verified":false}""",
    )

    ComRaid.login("user_123")

    val request = transport.requests.single()
    assertEquals("POST", request.method)
    assertEquals("/mobile/subscriptions/sub-1/login", request.path)
    assertEquals("cr_app_test", request.headers["X-ComRaid-App-Id"])
    val body = JSONObject(request.body!!)
    assertEquals("user_123", body.getString("external_user_id"))
    assertEquals("cred-1", body.getString("subscription_credential"))
    assertFalse(body.has("external_id"))
  }

  @Test
  fun login_includes_optional_jwt() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")
    transport.nextResponse = HttpResponse(
      statusCode = 200,
      body = """{"subscription_id":"sub-1","contact_id":"c-1","external_user_id":"user_123","anonymous":false,"verified":true}""",
    )

    ComRaid.login("user_123", jwt = "jwt-from-backend")

    val body = JSONObject(transport.requests.single().body!!)
    assertEquals("jwt-from-backend", body.getString("jwt"))
  }

  @Test
  fun logout_posts_to_logout_and_keeps_credential() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")
    transport.nextResponse = HttpResponse(
      statusCode = 200,
      body = """{"subscription_id":"sub-1","active":false,"verified":false}""",
    )

    ComRaid.logout()

    val request = transport.requests.single()
    assertEquals("/mobile/subscriptions/sub-1/logout", request.path)
    val body = JSONObject(request.body!!)
    assertEquals("cred-1", body.getString("subscription_credential"))
    assertEquals("sub-1", store.subscriptionId)
    assertEquals("cred-1", store.credential)
  }

  @Test
  fun optOut_posts_channel_push_at_contact_level() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")
    transport.nextResponse = HttpResponse(
      statusCode = 200,
      body = """{"subscription_id":"sub-1","contact_id":"c-1","channel":"push","opted_out":true}""",
    )

    ComRaid.optOut()

    val request = transport.requests.single()
    assertEquals("/mobile/subscriptions/sub-1/opt-out", request.path)
    val body = JSONObject(request.body!!)
    assertEquals("cred-1", body.getString("subscription_credential"))
    assertEquals("push", body.getString("channel"))
  }

  @Test
  fun optIn_posts_channel_push() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")
    transport.nextResponse = HttpResponse(
      statusCode = 200,
      body = """{"subscription_id":"sub-1","contact_id":"c-1","channel":"push","opted_out":false}""",
    )

    ComRaid.optIn()

    val request = transport.requests.single()
    assertEquals("/mobile/subscriptions/sub-1/opt-in", request.path)
    val body = JSONObject(request.body!!)
    assertEquals("push", body.getString("channel"))
  }

  @Test
  fun optOut_after_logout_still_uses_stored_credential() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")
    transport.enqueue(
      HttpResponse(200, """{"subscription_id":"sub-1","active":false,"verified":false}"""),
      HttpResponse(200, """{"subscription_id":"sub-1","contact_id":"c-1","channel":"push","opted_out":true}"""),
    )

    ComRaid.logout()
    ComRaid.optOut()

    assertEquals(2, transport.requests.size)
    assertEquals("/mobile/subscriptions/sub-1/opt-out", transport.requests[1].path)
    assertEquals("cred-1", JSONObject(transport.requests[1].body!!).getString("subscription_credential"))
  }

  @Test
  fun login_before_configure_throws_not_configured() {
    ComRaid.resetForTests()
    assertThrows(ComRaidException.NotConfigured::class.java) {
      ComRaid.login("user_123")
    }
  }

  @Test
  fun login_before_subscription_ready_throws_not_ready() {
    assertThrows(ComRaidException.NotReady::class.java) {
      ComRaid.login("user_123")
    }
  }

  @Test
  fun register_posts_android_platform_and_stores_credential() {
    transport.nextResponse = HttpResponse(
      statusCode = 201,
      body = """{"subscription_id":"sub-new","subscription_credential":"cred-new","channel":"push","anonymous":true,"platform":"android","provider":"fcm"}""",
    )

    ComRaid.register()

    val request = transport.requests.single()
    assertEquals("/mobile/subscriptions", request.path)
    assertEquals("cr_app_test", request.headers["X-ComRaid-App-Id"])
    val body = JSONObject(request.body!!)
    assertEquals("android", body.getString("platform"))
    assertEquals("fcm-token-1", body.getString("token"))
    assertEquals("sub-new", store.subscriptionId)
    assertEquals("cred-new", store.credential)
  }

  @Test
  fun token_refresh_with_credential_calls_replace_token() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")
    tokens.token = "fcm-token-2"
    transport.nextResponse = HttpResponse(
      statusCode = 200,
      body = """{"subscription_id":"sub-1","anonymous":true,"platform":"android","provider":"fcm","verified":false,"token_version":2}""",
    )

    ComRaid.onPushTokenRefresh()

    val request = transport.requests.single()
    assertEquals("/mobile/subscriptions/sub-1/token", request.path)
    val body = JSONObject(request.body!!)
    assertEquals("fcm-token-2", body.getString("token"))
    assertEquals("android", body.getString("platform"))
    assertEquals("fcm", body.getString("provider"))
    assertEquals("cred-1", body.getString("subscription_credential"))
  }

  @Test
  fun token_refresh_without_credential_registers() {
    tokens.token = "fcm-token-3"
    transport.nextResponse = HttpResponse(
      statusCode = 201,
      body = """{"subscription_id":"sub-3","subscription_credential":"cred-3","channel":"push","anonymous":true,"platform":"android","provider":"fcm"}""",
    )

    ComRaid.onPushTokenRefresh()

    assertEquals("/mobile/subscriptions", transport.requests.single().path)
    assertEquals("sub-3", store.subscriptionId)
  }

  @Test
  fun configure_requires_app_id() {
    ComRaid.resetForTests()
    assertThrows(IllegalArgumentException::class.java) {
      ComRaid.configureForTests(
        configuration = ComRaidConfiguration(appId = "  ", smallIconResourceId = 1),
        transport = transport,
        tokenProvider = tokens,
        subscriptionStore = store,
      )
    }
  }

  @Test
  fun no_old_wire_names_in_identity_requests() {
    store.save(subscriptionId = "sub-1", credential = "cred-1")
    transport.enqueue(
      HttpResponse(200, """{"subscription_id":"sub-1","contact_id":"c-1","external_user_id":"u","anonymous":false,"verified":false}"""),
      HttpResponse(200, """{"subscription_id":"sub-1","active":false,"verified":false}"""),
      HttpResponse(200, """{"subscription_id":"sub-1","contact_id":"c-1","channel":"push","opted_out":true}"""),
      HttpResponse(200, """{"subscription_id":"sub-1","contact_id":"c-1","channel":"push","opted_out":false}"""),
    )

    ComRaid.login("u")
    ComRaid.logout()
    ComRaid.optOut()
    ComRaid.optIn()

    transport.requests.forEach { request ->
      assertFalse(request.body!!.contains("external_id"))
      assertTrue(request.path.startsWith("/mobile/subscriptions"))
    }
  }

  private class RecordingTransport : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var nextResponse: HttpResponse? = null
    private val queue = ArrayDeque<HttpResponse>()

    fun enqueue(vararg responses: HttpResponse) {
      queue.clear()
      queue.addAll(responses)
    }

    override fun execute(request: HttpRequest): HttpResponse {
      requests += request
      return when {
        queue.isNotEmpty() -> queue.removeFirst()
        nextResponse != null -> nextResponse!!
        else -> error("No response configured")
      }
    }
  }

  private class FakeTokenProvider(var token: String) : TokenProvider {
    override fun currentToken(): String = token
  }
}
