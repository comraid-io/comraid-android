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
        "cr_subtitle" to "Order update",
        "cr_sound" to "chime",
        "cr_badge" to "3",
        "cr_thread_id" to "order-42",
        "cr_android_channel_id" to "orders",
        "cr_priority" to "normal",
        "order_id" to "42",
      ),
    )
    assertEquals("https://cdn.example.com/a.png", event.imageUrl)
    assertEquals("Order update", event.subtitle)
    assertEquals("chime", event.sound)
    assertEquals(3, event.badge)
    assertEquals("order-42", event.threadId)
    assertEquals("orders", event.androidChannelId)
    assertEquals("normal", event.priority)
    assertEquals(mapOf("order_id" to "42"), event.data)
  }

  @Test
  fun presentation_fields_are_null_when_absent() {
    val event = RemoteMessageMapper.toEvent(mapOf("cr_title" to "Hello", "cr_badge" to "lots"))
    assertNull(event.imageUrl)
    assertNull(event.subtitle)
    assertNull(event.sound)
    assertNull(event.badge)
    assertNull(event.threadId)
    assertNull(event.androidChannelId)
    assertEquals("high", event.priority)
  }

  @Test
  fun blank_subtitle_is_null() {
    val event = RemoteMessageMapper.toEvent(
      mapOf("cr_title" to "Hello", "cr_subtitle" to "   "),
    )
    assertNull(event.subtitle)
  }

  @Test
  fun round_trip_preserves_presentation_keys() {
    val payload = mapOf(
      "cr_message_id" to "m1",
      "cr_delivery_id" to "d1",
      "cr_attribution_token" to "tok",
      "cr_title" to "Hello",
      "cr_body" to "World",
      "cr_deep_link" to "app://orders/42",
      "cr_actions" to """[{"id":"accept","text":"Accept"}]""",
      "cr_image_url" to "https://cdn.example.com/a.png",
      "cr_subtitle" to "Order update",
      "cr_sound" to "chime",
      "cr_badge" to "3",
      "cr_thread_id" to "order-42",
      "cr_android_channel_id" to "orders",
      "cr_priority" to "normal",
      "order_id" to "42",
    )
    val restored = RemoteMessageMapper.toPayload(RemoteMessageMapper.toEvent(payload))
    assertEquals("m1", restored["cr_message_id"])
    assertEquals("d1", restored["cr_delivery_id"])
    assertEquals("tok", restored["cr_attribution_token"])
    assertEquals("Hello", restored["cr_title"])
    assertEquals("World", restored["cr_body"])
    assertEquals("app://orders/42", restored["cr_deep_link"])
    assertEquals("https://cdn.example.com/a.png", restored["cr_image_url"])
    assertEquals("Order update", restored["cr_subtitle"])
    assertEquals("chime", restored["cr_sound"])
    assertEquals("3", restored["cr_badge"])
    assertEquals("order-42", restored["cr_thread_id"])
    assertEquals("orders", restored["cr_android_channel_id"])
    assertEquals("normal", restored["cr_priority"])
    assertEquals("42", restored["order_id"])
  }

  @Test
  fun round_trip_omits_absent_optional_presentation_keys() {
    val restored = RemoteMessageMapper.toPayload(
      RemoteMessageMapper.toEvent(mapOf("cr_title" to "Hello", "cr_body" to "World")),
    )
    assertEquals("Hello", restored["cr_title"])
    assertEquals("World", restored["cr_body"])
    assertEquals("high", restored["cr_priority"])
    assertNull(restored["cr_image_url"])
    assertNull(restored["cr_subtitle"])
    assertNull(restored["cr_sound"])
    assertNull(restored["cr_badge"])
    assertNull(restored["cr_thread_id"])
    assertNull(restored["cr_android_channel_id"])
  }
}
