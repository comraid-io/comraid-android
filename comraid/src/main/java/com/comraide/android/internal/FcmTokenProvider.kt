package com.comraide.android.internal

import com.comraide.android.ComraidException
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reads the FCM registration token via Firebase Messaging.
 * The host app must put Firebase Messaging on the classpath.
 */
internal class FcmTokenProvider : TokenProvider {
  override fun currentToken(): String {
    val latch = CountDownLatch(1)
    var token: String? = null
    var error: Exception? = null
    FirebaseMessaging.getInstance().token
      .addOnCompleteListener { task ->
        if (task.isSuccessful) {
          token = task.result
        } else {
          error = task.exception ?: IllegalStateException("FCM token request failed")
        }
        latch.countDown()
      }
    if (!latch.await(30, TimeUnit.SECONDS)) {
      throw ComraidException.ApiError("Timed out waiting for FCM registration token")
    }
    error?.let { throw ComraidException.ApiError("Could not obtain FCM token: ${it.message}") }
    return token?.takeIf { it.isNotBlank() }
      ?: throw ComraidException.ApiError("FCM registration token was empty")
  }
}
