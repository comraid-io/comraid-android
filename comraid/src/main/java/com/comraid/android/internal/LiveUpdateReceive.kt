package com.comraid.android.internal

import android.content.Context

/**
 * The FCM receive path for Live activity data messages. [ComraidMessagingService] calls this
 * first; a live update is never shown to the host as an ordinary message and has no Delivery to ACK.
 */
internal object LiveUpdateReceive {
  /** True when [data] was a live update (handled here), false for an ordinary message. */
  fun handleFcmData(context: Context, data: Map<String, String>?): Boolean {
    val message = LiveUpdate.fromData(data) ?: return false
    // This runs inside the host's messaging service: a failure to draw must never crash it.
    runCatching { draw(context, message) }
    return true
  }

  private fun draw(context: Context, message: LiveUpdate.Message) {
    // Config is persisted by configure(), so this works in a process started only for FCM.
    val configuration = SdkConfigStore(context).load() ?: return
    val store = LiveUpdateStore(context)

    when (message.op) {
      LiveUpdate.Op.START -> {
        store.clearDismissed(message.activityId)
        store.clearEnded(message.activityId)
        LiveUpdateNotifier.showOngoing(context, configuration, message)
      }
      LiveUpdate.Op.UPDATE -> {
        // Gone for good: the user swiped it away, or the server already ended it (FCM can deliver a
        // late update after the end).
        if (!store.isDismissed(message.activityId) && !store.isEnded(message.activityId)) {
          LiveUpdateNotifier.showOngoing(context, configuration, message)
        }
      }
      LiveUpdate.Op.END -> {
        store.clearDismissed(message.activityId)
        store.markEnded(message.activityId)
        // The final state if the server sent one, otherwise just remove it.
        if (message.title != null || message.body != null) {
          LiveUpdateNotifier.showFinal(context, configuration, message)
        } else {
          LiveUpdateNotifier.cancel(context, message.activityId)
        }
      }
    }
  }
}
