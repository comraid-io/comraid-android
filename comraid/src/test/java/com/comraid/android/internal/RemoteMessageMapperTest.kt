package com.comraid.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteMessageMapperTest {
  @Test
  fun maps_event_fields_and_strips_reserved_keys() {
    val event = RemoteMessageMapper.toEvent(
      mapOf(
        "cr_message_id" to "m1",
        "cr_delivery_id" to "d1",
        "cr_title" to "Hello",
        "cr_body" to "World",
        "cr_deep_link" to "app://x",
        "cr_actions" to """[{"id":"accept","text":"Accept"}]""",
        "cr_attribution_token" to "tok",
        "path" to "/home",
      ),
      actionId = "accept",
    )
    assertEquals("m1", event.messageId)
    assertEquals("d1", event.deliveryId)
    assertEquals("tok", event.attributionToken)
    assertEquals("Hello", event.title)
    assertEquals("World", event.body)
    assertEquals("app://x", event.deepLink)
    assertEquals(mapOf("path" to "/home"), event.data)
    assertEquals(1, event.actions.size)
    assertEquals("accept", event.actions[0].id)
    assertEquals("accept", event.actionId)
  }

  @Test
  fun blank_optional_fields_are_null() {
    val event = RemoteMessageMapper.toEvent(mapOf("path" to "/x"))
    assertNull(event.messageId)
    assertNull(event.deliveryId)
    assertNull(event.title)
    assertNull(event.body)
    assertNull(event.deepLink)
    assertEquals(mapOf("path" to "/x"), event.data)
  }

  @Test
  fun carries_presentation_fields_outside_data() {
    val event = RemoteMessageMapper.toEvent(
      mapOf(
        "cr_title" to "Hello",
        "cr_image_url" to "https://cdn.example.com/a.png",
        "cr_sound" to "chime",
        "cr_badge" to "3",
        "cr_thread_id" to "order-42",
        "cr_android_channel_id" to "orders",
        "order_id" to "42",
      ),
    )
    assertEquals("https://cdn.example.com/a.png", event.imageUrl)
    assertEquals("chime", event.sound)
    assertEquals(3, event.badge)
    assertEquals("order-42", event.threadId)
    assertEquals("orders", event.androidChannelId)
    assertEquals(mapOf("order_id" to "42"), event.data)
  }

  @Test
  fun presentation_fields_are_null_when_absent() {
    val event = RemoteMessageMapper.toEvent(mapOf("cr_title" to "Hello", "cr_badge" to "lots"))
    assertNull(event.imageUrl)
    assertNull(event.sound)
    assertNull(event.badge)
    assertNull(event.threadId)
    assertNull(event.androidChannelId)
  }
}
