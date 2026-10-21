package com.comraid.android.internal

import com.google.firebase.messaging.FirebaseMessagingService

/**
 * Optional base [FirebaseMessagingService]. Host apps may declare this class (or a subclass)
 * in their manifest so FCM token rotation calls [com.comraid.android.ComRaid.onPushTokenRefresh].
 */
open class ComraidMessagingService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    runCatching { com.comraid.android.ComRaid.onPushTokenRefresh() }
  }
}
