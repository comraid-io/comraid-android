package com.comraid.android.internal

import com.comraid.android.ComRaidException
import com.comraid.android.DeviceProperties
import org.json.JSONObject

internal class MobileApiClient(
  private val appId: String,
  private val transport: HttpTransport,
) {
  fun register(token: String, properties: DeviceProperties?): RegisterResult {
    val body = JSONObject()
      .put("platform", "android")
      .put("token", token)
      .put("provider", "fcm")
    putDeviceFields(body, properties)
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

  fun replaceToken(
    subscriptionId: String,
    credential: String,
    token: String,
    properties: DeviceProperties?,
  ) {
    val body = JSONObject()
      .put("token", token)
      .put("platform", "android")
      .put("provider", "fcm")
      .put("subscription_credential", credential)
    putDeviceFields(body, properties)
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

  fun upsertAttributes(externalUserId: String, attributes: Map<String, Any?>) {
    val attrs = JSONObject()
    attributes.forEach { (key, value) -> attrs.put(key, value ?: JSONObject.NULL) }
    val body = JSONObject().put("attributes", attrs)
    val response = put("/contacts/$externalUserId/attributes", body)
    requireSuccess(response, expected = setOf(200))
  }

  fun listNotificationCategories(): List<NotificationCategory> {
    val response = transport.execute(
      HttpRequest(
        method = "GET",
        path = "/mobile/notification-categories?platform=android",
        headers = mapOf("X-ComRaid-App-Id" to appId),
        body = null,
      ),
    )
    if (response.statusCode == 304) return emptyList()
    requireSuccess(response, expected = setOf(200))
    val categories = JSONObject(response.body).optJSONArray("categories") ?: return emptyList()
    return buildList {
      for (i in 0 until categories.length()) {
        val obj = categories.optJSONObject(i) ?: continue
        val channelId = obj.optString("android_channel_id").takeIf { it.isNotBlank() } ?: continue
        add(
          NotificationCategory(
            slug = obj.optString("slug"),
            name = obj.optString("name").ifBlank { channelId },
            androidChannelId = channelId,
            androidImportance = obj.optString("android_importance").ifBlank { "default" },
            androidLights = obj.optBoolean("android_lights"),
            androidVibration = obj.opt("android_vibration"),
            isDefault = obj.optBoolean("is_default"),
          ),
        )
      }
    }
  }

  private fun putDeviceFields(body: JSONObject, properties: DeviceProperties?) {
    if (properties == null) return
    properties.appVersion?.takeIf { it.isNotBlank() }?.let { body.put("app_version", it) }
    val device = JSONObject()
    properties.model?.takeIf { it.isNotBlank() }?.let { device.put("model", it) }
    properties.osVersion?.takeIf { it.isNotBlank() }?.let { device.put("os_version", it) }
    properties.extras.forEach { (key, value) ->
      if (value == null) device.put(key, JSONObject.NULL) else device.put(key, value)
    }
    if (device.length() > 0) {
      body.put("device", device)
    }
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

  private fun put(path: String, body: JSONObject): HttpResponse =
    transport.execute(
      HttpRequest(
        method = "PUT",
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
      throw ComRaidException.ApiError(message)
    }
  }

  internal data class RegisterResult(
    val subscriptionId: String,
    val credential: String?,
  )

  internal data class NotificationCategory(
    val slug: String,
    val name: String,
    val androidChannelId: String,
    val androidImportance: String,
    val androidLights: Boolean,
    val androidVibration: Any?,
    val isDefault: Boolean,
  )
}
