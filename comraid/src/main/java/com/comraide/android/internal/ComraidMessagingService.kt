package com.comraide.android.internal

import com.google.firebase.messaging.FirebaseMessagingService

/**
 * Optional base [FirebaseMessagingService]. Host apps may declare this class (or a subclass)
 * in their manifest so FCM token rotation calls [com.comraide.android.Comraid.onPushTokenRefresh].
 */
open class ComraidMessagingService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    runCatching { com.comraide.android.Comraid.onPushTokenRefresh() }
  }
}
