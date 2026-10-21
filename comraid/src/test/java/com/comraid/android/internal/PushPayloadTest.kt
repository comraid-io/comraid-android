package com.comraid.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPayloadTest {
  @Test
  fun attribution_requires_delivery_id_and_token() {
    assertNull(PushPayload.attribution(mapOf("cr_delivery_id" to "d1")))
    assertNull(PushPayload.attribution(emptyMap()))
    val attr = PushPayload.attribution(
      mapOf("cr_delivery_id" to "d1", "cr_attribution_token" to "tok"),
    )!!
    assertEquals("d1", attr.deliveryId)
    assertEquals("tok", attr.attributionToken)
  }

  @Test
  fun display_reads_cr_title_body_channel_and_image() {
    val display = PushPayload.display(
      mapOf(
        "cr_title" to "Hello",
        "cr_body" to "World",
        "cr_image_url" to "https://img",
        "cr_android_channel_id" to "general",
      ),
    )
    assertEquals("Hello", display.title)
    assertEquals("World", display.body)
    assertEquals("https://img", display.imageUrl)
    assertEquals("general", display.androidChannelId)
  }

  @Test
  fun actions_parse_cr_actions_json() {
    val actions = PushPayload.actions(
      mapOf("cr_actions" to """[{"id":"accept","text":"Accept","icon":"ic_ok"},{"id":"x"}]"""),
    )
    assertEquals(1, actions.size)
    assertEquals("accept", actions[0].id)
    assertEquals("Accept", actions[0].text)
    assertEquals("ic_ok", actions[0].icon)
  }

  @Test
  fun fromFcmData_fills_title_body_from_notification_block_when_missing() {
    val data = PushPayload.fromFcmData(
      data = mapOf("cr_delivery_id" to "d1"),
      notificationTitle = "NTitle",
      notificationBody = "NBody",
    )
    assertEquals("NTitle", data["cr_title"])
    assertEquals("NBody", data["cr_body"])
    assertTrue(data.containsKey("cr_delivery_id"))
  }
}
