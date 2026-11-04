package com.comraid.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileApiClientCategoriesTest {
  @Test
  fun listNotificationCategories_reads_android_sound() {
    val body = """
      {"categories":[
        {"slug":"orders","name":"Orders","android_channel_id":"orders","android_importance":"high","android_sound":"chime"},
        {"slug":"general","name":"General","android_channel_id":"general"}
      ]}
    """.trimIndent()
    val api = MobileApiClient("cr_app_test", HttpTransport { HttpResponse(200, body) })

    val categories = api.listNotificationCategories()

    assertEquals(2, categories.size)
    assertEquals("chime", categories[0].androidSound)
    assertNull(categories[1].androidSound)
  }
}
