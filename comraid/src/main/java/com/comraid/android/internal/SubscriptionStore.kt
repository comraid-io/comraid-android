package com.comraid.android.internal

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
    const val FILE_NAME = "comraid.subscription.secure"
    const val LEGACY_FILE_NAME = "comraide.subscription.secure"

    fun createSecurePreferences(context: Context): SharedPreferences {
      val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
      val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
      )
      migrateLegacy(context, masterKey, prefs)
      return prefs
    }

    fun migrateLegacy(context: Context, masterKey: MasterKey, prefs: SharedPreferences) {
      if (prefs.contains(KEY_ID) || prefs.contains(KEY_CREDENTIAL)) return
      val legacy = runCatching {
        EncryptedSharedPreferences.create(
          context,
          LEGACY_FILE_NAME,
          masterKey,
          EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
          EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
      }.getOrNull() ?: return
      val id = legacy.getString(KEY_ID, null)
      val credential = legacy.getString(KEY_CREDENTIAL, null)
      if (id == null && credential == null) return
      prefs.edit {
        if (id != null) putString(KEY_ID, id)
        if (credential != null) putString(KEY_CREDENTIAL, credential)
      }
      legacy.edit { clear() }
    }
  }
}
