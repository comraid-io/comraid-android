package com.comraid.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPayloadEventMappingTest {
  @Test
  fun isDataOnly_reads_cr_data_only() {
    assertFalse(PushPayload.isDataOnly(null))
    assertFalse(PushPayload.isDataOnly(emptyMap()))
    assertFalse(PushPayload.isDataOnly(mapOf("cr_data_only" to "false")))
    assertTrue(PushPayload.isDataOnly(mapOf("cr_data_only" to "true")))
    assertTrue(PushPayload.isDataOnly(mapOf("cr_data_only" to "1")))
    assertTrue(PushPayload.isDataOnly(mapOf("cr_data_only" to " TRUE ")))
  }

  @Test
  fun messageId_reads_cr_message_id() {
    assertNull(PushPayload.messageId(emptyMap()))
    assertEquals("m1", PushPayload.messageId(mapOf("cr_message_id" to "m1")))
  }

  @Test
  fun senderData_strips_cr_keys_preserves_app_keys() {
    val data = mapOf(
      "path" to "/orders/1",
      "params" to """{"id":1}""",
      "cr_delivery_id" to "d1",
      "cr_attribution_token" to "tok",
      "cr_message_id" to "m1",
      "cr_title" to "Hi",
      "cr_body" to "Body",
      "cr_deep_link" to "app://x",
      "cr_actions" to "[]",
      "cr_data_only" to "true",
      "cr_image_url" to "https://x",
      "cr_android_channel_id" to "general",
    )
    val sender = PushPayload.senderData(data)
    assertEquals(mapOf("path" to "/orders/1", "params" to """{"id":1}"""), sender)
  }

  @Test
  fun openIntentExtras_round_trip_cr_keys() {
    val source = mapOf(
      "cr_delivery_id" to "d1",
      "cr_attribution_token" to "tok",
      "cr_message_id" to "m1",
      "cr_title" to "Hi",
      "path" to "/home",
    )
    val extras = PushPayload.openIntentExtras(source)
    assertEquals("d1", extras["cr_delivery_id"])
    assertEquals("tok", extras["cr_attribution_token"])
    assertEquals("m1", extras["cr_message_id"])
    assertEquals("Hi", extras["cr_title"])
    assertEquals("/home", extras["path"])
    val restored = PushPayload.fromIntentExtras(extras)
    assertEquals(source, restored)
  }
}
