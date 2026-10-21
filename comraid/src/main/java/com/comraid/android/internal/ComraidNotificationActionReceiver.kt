package com.comraid.android.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Handles notification tap / action buttons: enqueues opened ACK and flushes.
 * Works from persisted [SdkConfigStore] without requiring foreground ComRaid init.
 */
class ComraidNotificationActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent == null) return
    val raw = intent.getStringExtra(EXTRA_DATA_JSON) ?: return
    val data = buildMap {
      val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
      obj.keys().forEach { key -> put(key, obj.optString(key)) }
    }
    val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
    val helpers = BackgroundAckHelpers.from(context) ?: return
    helpers.queue.enqueueFromPayload(AckKind.OPENED, data, actionId)
    helpers.queue.flush()
  }

  companion object {
    const val EXTRA_KIND = "com.comraid.ack.kind"
    const val EXTRA_ACTION_ID = "com.comraid.ack.action_id"
    const val EXTRA_DATA_JSON = "com.comraid.ack.data_json"
  }
}
