package com.comraid.android.internal

internal class InMemorySubscriptionStore : SubscriptionStore {
  override var subscriptionId: String? = null
    private set
  override var credential: String? = null
    private set

  override fun save(subscriptionId: String, credential: String?) {
    this.subscriptionId = subscriptionId
    if (credential != null) {
      this.credential = credential
    }
  }

  override fun clear() {
    subscriptionId = null
    credential = null
  }
}
