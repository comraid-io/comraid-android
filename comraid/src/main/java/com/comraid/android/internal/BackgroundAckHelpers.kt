package com.comraid.android.internal

import android.content.Context

internal data class BackgroundAckHelpers(
  val queue: AckQueue,
  val configuration: com.comraid.android.ComRaidConfiguration,
) {
  companion object {
    fun from(context: Context): BackgroundAckHelpers? {
      val appContext = context.applicationContext
      val configuration = SdkConfigStore(appContext).load() ?: return null
      val transport = UrlConnectionHttpTransport(configuration.apiBaseUrl)
      val queue = AckQueue(
        apiBaseUrl = configuration.apiBaseUrl,
        store = SharedPreferencesAckQueueStore(appContext),
        transport = transport,
      )
      return BackgroundAckHelpers(queue = queue, configuration = configuration)
    }
  }
}
