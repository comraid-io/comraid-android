package com.comraid.android.internal

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

internal interface SubscriptionStore {
  val subscriptionId: String?
  val credential: String?
  val externalUserId: String?
  fun save(subscriptionId: String, credential: String?)
  fun setExternalUserId(externalUserId: String?)
  fun clear()
}

internal class SharedPreferencesSubscriptionStore(context: Context) : SubscriptionStore {
  private val preferences: SharedPreferences = createSecurePreferences(context)

  override val subscriptionId: String?
    get() = preferences.getString(KEY_ID, null)

  override val credential: String?
    get() = preferences.getString(KEY_CREDENTIAL, null)

  override val externalUserId: String?
    get() = preferences.getString(KEY_EXTERNAL_USER_ID, null)

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
  override fun setExternalUserId(externalUserId: String?) {
    preferences.edit {
      if (externalUserId == null) {
        remove(KEY_EXTERNAL_USER_ID)
      } else {
        putString(KEY_EXTERNAL_USER_ID, externalUserId)
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
    const val KEY_EXTERNAL_USER_ID = "external_user_id"
    const val FILE_NAME = "comraid.subscription.secure"

    fun createSecurePreferences(context: Context): SharedPreferences {
      // security-crypto 1.0.0 (stable). MasterKey lives only in 1.1.0-alpha*.
      val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
      return EncryptedSharedPreferences.create(
        FILE_NAME,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
      )
    }
  }
}
