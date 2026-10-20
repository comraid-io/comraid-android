package com.comraide.android.internal

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal interface SubscriptionStore {
  val subscriptionId: String?
  val credential: String?
  fun save(subscriptionId: String, credential: String?)
  fun clear()
}

internal class SharedPreferencesSubscriptionStore(context: Context) : SubscriptionStore {
  private val preferences: SharedPreferences = createSecurePreferences(context)

  override val subscriptionId: String?
    get() = preferences.getString(KEY_ID, null)

  override val credential: String?
    get() = preferences.getString(KEY_CREDENTIAL, null)

  @Synchronized
  override fun save(subscriptionId: String, credential: String?) {
    preferences.edit {
      putString(KEY_ID, subscriptionId)
      if (credential != null) {
        putString(KEY_CREDENTIAL, credential)
      }
    }
  }

  @Synchronized
  override fun clear() {
    preferences.edit { clear() }
  }

  private companion object {
    const val KEY_ID = "subscription_id"
    const val KEY_CREDENTIAL = "subscription_credential"
    const val FILE_NAME = "comraide.subscription.secure"

    fun createSecurePreferences(context: Context): SharedPreferences {
      val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
      return EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
      )
    }
  }
}
