package com.comraid.android.internal

/**
 * Supplies the current FCM registration token.
 * Host apps provide Firebase Messaging on the classpath; production uses
 * [FcmTokenProvider]. Tests inject a fake.
 */
fun interface TokenProvider {
  fun currentToken(): String
}
