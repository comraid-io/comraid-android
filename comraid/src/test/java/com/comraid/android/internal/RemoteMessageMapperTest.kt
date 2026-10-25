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
}
