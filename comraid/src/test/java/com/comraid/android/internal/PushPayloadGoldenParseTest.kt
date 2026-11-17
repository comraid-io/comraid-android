package com.comraid.android.internal

import com.comraid.android.RemoteMessageAction
import com.comraid.android.RemoteMessageEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * Golden parse at the remote-message mapper / Push payload parser seam:
 * each vendored golden's `pushPayload` → [RemoteMessageMapper.toEvent] equals `expectedEvent`.
 */
@RunWith(Parameterized::class)
class PushPayloadGoldenParseTest(private val goldenFile: File) {
  @Test
  fun pushPayload_parses_to_expectedEvent() {
    val golden = JSONObject(goldenFile.readText())
    val data = dataMapFromPushPayload(golden.getJSONObject("pushPayload"))
    val actual = RemoteMessageMapper.toEvent(data)
    val expected = expectedEventFromJson(golden.getJSONObject("expectedEvent"))
    assertEquals(goldenFile.name, expected, actual)
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun goldens(): List<Array<Any>> {
      val dir = File(locateDir("contracts/push-payload", "../contracts/push-payload"), "goldens")
      require(dir.isDirectory) { "missing vendored goldens under $dir" }
      val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }
        ?: emptyList()
      require(files.isNotEmpty()) { "no golden JSON files in $dir" }
      return files.map { arrayOf(it) }
    }

    private fun locateDir(vararg candidates: String): File =
      candidates.map(::File).firstOrNull { it.isDirectory }
        ?: error("could not locate dir ${candidates.joinToString()} from ${System.getProperty("user.dir")}")

    /**
     * FCM goldens nest keys under `data`; APNs goldens are top-level custom keys plus `aps`.
     * Android production only sees FCM `data` maps. For APNs goldens, flatten the same way
     * iOS [fromUserInfo] does so shared expectedEvent values (aps-sourced title/sound/…) apply
     * at the mapper seam without changing Android presentation behaviour.
     */
    internal fun dataMapFromPushPayload(pushPayload: JSONObject): Map<String, String> {
      val dataObj = pushPayload.optJSONObject("data")
      if (dataObj != null) {
        return stringMapFromJson(dataObj)
      }
      return flattenApnsStyle(pushPayload)
    }

    private fun stringMapFromJson(obj: JSONObject): Map<String, String> {
      val out = linkedMapOf<String, String>()
      val keys = obj.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        val value = obj.get(key)
        if (value is String) {
          out[key] = value
        } else if (value is Number) {
          out[key] = value.toString()
        }
      }
      return out
    }

    private fun flattenApnsStyle(userInfo: JSONObject): Map<String, String> {
      val out = stringMapFromJson(userInfo).toMutableMap()
      val aps = userInfo.optJSONObject("aps") ?: return out
      fillIfMissing(out, "cr_sound", apsSoundName(aps))
      val badge = aps.opt("badge")
      if (badge is Number) {
        fillIfMissing(out, "cr_badge", badge.toInt().toString())
      } else if (badge is String) {
        fillIfMissing(out, "cr_badge", badge)
      }
      fillIfMissing(out, "cr_thread_id", aps.optString("thread-id").takeIf { it.isNotEmpty() })
      if (out["cr_title"].isNullOrEmpty()) {
        apsAlertString(aps, "title")?.takeIf { it.isNotEmpty() }?.let { out["cr_title"] = it }
      }
      if (out["cr_body"].isNullOrEmpty()) {
        apsAlertString(aps, "body")?.takeIf { it.isNotEmpty() }?.let { out["cr_body"] = it }
      }
      if (out["cr_subtitle"].isNullOrEmpty()) {
        apsAlertString(aps, "subtitle")?.takeIf { it.isNotEmpty() }?.let { out["cr_subtitle"] = it }
      }
      return out
    }

    private fun apsSoundName(aps: JSONObject): String? {
      val sound = aps.opt("sound") ?: return null
      if (sound is String) return sound
      if (sound is JSONObject) {
        return sound.optString("name").takeIf { it.isNotEmpty() }
      }
      return null
    }

    private fun apsAlertString(aps: JSONObject, key: String): String? {
      val alert = aps.opt("alert") ?: return null
      if (alert is JSONObject) {
        return alert.optString(key).takeIf { it.isNotEmpty() }
      }
      if (key == "body" && alert is String) {
        return alert
      }
      return null
    }

    private fun fillIfMissing(out: MutableMap<String, String>, key: String, value: String?) {
      if (!out[key].isNullOrBlank() || value.isNullOrBlank()) return
      out[key] = value
    }

    internal fun expectedEventFromJson(obj: JSONObject): RemoteMessageEvent {
      return RemoteMessageEvent(
        messageId = obj.nullableString("messageId"),
        deliveryId = obj.nullableString("deliveryId"),
        attributionToken = obj.nullableString("attributionToken"),
        title = obj.nullableString("title"),
        body = obj.nullableString("body"),
        data = stringMapFromJson(obj.optJSONObject("data") ?: JSONObject()),
        deepLink = obj.nullableString("deepLink"),
        actions = actionsFromJson(obj.optJSONArray("actions") ?: JSONArray()),
        actionId = obj.nullableString("actionId"),
        imageUrl = obj.nullableString("imageUrl"),
        subtitle = obj.nullableString("subtitle"),
        sound = obj.nullableString("sound"),
        badge = if (obj.isNull("badge")) null else obj.getInt("badge"),
        threadId = obj.nullableString("threadId"),
        androidChannelId = obj.nullableString("androidChannelId"),
        priority = obj.optString("priority", "high"),
      )
    }

    private fun actionsFromJson(array: JSONArray): List<RemoteMessageAction> {
      return buildList {
        for (i in 0 until array.length()) {
          val item = array.getJSONObject(i)
          add(
            RemoteMessageAction(
              id = item.getString("id"),
              text = item.getString("text"),
              icon = if (item.isNull("icon") || !item.has("icon")) {
                null
              } else {
                item.getString("icon")
              },
            ),
          )
        }
      }
    }

    private fun JSONObject.nullableString(key: String): String? {
      if (!has(key) || isNull(key)) return null
      val value = getString(key)
      return value
    }
  }
}
