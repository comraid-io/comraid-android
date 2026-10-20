package com.comraide.android.internal

import com.comraide.android.ComraidException
import org.json.JSONObject

internal class MobileApiClient(
  private val appId: String,
  private val transport: HttpTransport,
) {
  fun register(token: String): RegisterResult {
    val body = JSONObject()
      .put("platform", "android")
      .put("token", token)
      .put("provider", "fcm")
    val response = post("/mobile/subscriptions", body)
    requireSuccess(response, expected = setOf(200, 201))
    val json = JSONObject(response.body)
    val credential = if (json.has("subscription_credential") && !json.isNull("subscription_credential")) {
      json.getString("subscription_credential").takeIf { it.isNotBlank() }
    } else {
      null
    }
    return RegisterResult(
      subscriptionId = json.getString("subscription_id"),
      credential = credential,
    )
  }

  fun replaceToken(subscriptionId: String, credential: String, token: String) {
    val body = JSONObject()
      .put("token", token)
      .put("platform", "android")
      .put("provider", "fcm")
      .put("subscription_credential", credential)
    val response = post("/mobile/subscriptions/$subscriptionId/token", body)
    requireSuccess(response, expected = setOf(200))
  }

  fun login(
    subscriptionId: String,
    credential: String,
    externalUserId: String,
    jwt: String?,
  ) {
    val body = JSONObject()
      .put("external_user_id", externalUserId)
      .put("subscription_credential", credential)
    if (jwt != null) {
      body.put("jwt", jwt)
    }
    val response = post("/mobile/subscriptions/$subscriptionId/login", body)
    requireSuccess(response, expected = setOf(200))
  }

  fun logout(subscriptionId: String, credential: String) {
    val body = JSONObject().put("subscription_credential", credential)
    val response = post("/mobile/subscriptions/$subscriptionId/logout", body)
    requireSuccess(response, expected = setOf(200))
  }

  fun optOut(subscriptionId: String, credential: String, channel: String) {
    val body = JSONObject()
      .put("subscription_credential", credential)
      .put("channel", channel)
    val response = post("/mobile/subscriptions/$subscriptionId/opt-out", body)
    requireSuccess(response, expected = setOf(200))
  }

  fun optIn(subscriptionId: String, credential: String, channel: String) {
    val body = JSONObject()
      .put("subscription_credential", credential)
      .put("channel", channel)
    val response = post("/mobile/subscriptions/$subscriptionId/opt-in", body)
    requireSuccess(response, expected = setOf(200))
  }

  private fun post(path: String, body: JSONObject): HttpResponse =
    transport.execute(
      HttpRequest(
        method = "POST",
        path = path,
        headers = mapOf("X-ComRaid-App-Id" to appId),
        body = body.toString(),
      ),
    )

  private fun requireSuccess(response: HttpResponse, expected: Set<Int>) {
    if (response.statusCode !in expected) {
      val message = runCatching {
        JSONObject(response.body).optJSONObject("error")?.optString("message")
      }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "HTTP ${response.statusCode}"
      throw ComraidException.ApiError(message)
    }
  }

  internal data class RegisterResult(
    val subscriptionId: String,
    val credential: String?,
  )
}
