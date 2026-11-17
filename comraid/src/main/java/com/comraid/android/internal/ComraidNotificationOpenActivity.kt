package com.comraid.android.internal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.comraid.android.ComRaid
import org.json.JSONObject

/**
 * Activity trampoline for remote notification body/action taps (targetSdk ≥ 31 safe).
 * Records opened ACK, surfaces the open (including `deepLink`) to listeners, then
 * brings the host launch activity forward. Never fires [Intent.ACTION_VIEW].
 */
class ComraidNotificationOpenActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handle(intent)
    finish()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handle(intent)
    finish()
  }

  private fun handle(intent: Intent?) {
    if (intent == null) return
    val data = dataFromIntent(intent) ?: return
    val actionId = intent.getStringExtra(EXTRA_ACTION_ID)?.takeIf { it.isNotBlank() }
    // Prefer the configured runtime queue; ackContext covers opens before configure().
    ComRaid.handleNotificationOpen(data, actionId, coldStart = false, ackContext = this)
    launchApp(this, data)
  }

  companion object {
    const val EXTRA_ACTION_ID = "com.comraid.open.action_id"
    const val EXTRA_DATA_JSON = "com.comraid.open.data_json"

    fun contentIntent(
      context: Context,
      payload: Map<String, String>,
      actionId: String? = null,
    ): Intent {
      val deliveryId = payload["cr_delivery_id"] ?: "comraid-remote"
      val uri = if (actionId.isNullOrBlank()) {
        Uri.parse(PushPayload.contentOpenUri(deliveryId, payload))
      } else {
        Uri.parse("comraid://action/$deliveryId/$actionId")
      }
      return Intent(context, ComraidNotificationOpenActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        setData(uri)
        putExtra(EXTRA_DATA_JSON, PushPayload.toJsonObject(payload).toString())
        if (!actionId.isNullOrBlank()) {
          putExtra(EXTRA_ACTION_ID, actionId)
        }
        PushPayload.openIntentExtras(payload).forEach { (k, v) -> putExtra(k, v) }
      }
    }

    fun dataFromIntent(intent: Intent): Map<String, String>? {
      val raw = intent.getStringExtra(EXTRA_DATA_JSON)
      if (!raw.isNullOrBlank()) {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return buildMap {
          obj.keys().forEach { key -> put(key, obj.optString(key)) }
        }
      }
      // System-tray / launcher: cr_* (and sender) extras on the launch intent.
      val extras = intent.extras ?: return null
      val mapped = buildMap<String, String?> {
        extras.keySet().forEach { key ->
          put(key, extras.getString(key))
        }
      }
      val restored = PushPayload.fromIntentExtras(mapped)
      if (restored["cr_delivery_id"].isNullOrBlank()) return null
      return restored
    }

    fun launchApp(context: Context, data: Map<String, String>) {
      when (val target = HostOpenLaunch.resolve(data)) {
        is HostOpenLaunch.Target.PackageLauncher -> {
          val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
              target.extras.forEach { (k, v) -> putExtra(k, v) }
            } ?: return
          runCatching { context.startActivity(launcher) }
        }
      }
    }
  }
}
