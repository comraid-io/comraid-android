package com.comraid.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Seam: what a Live activity FCM data message looks like to the SDK (contract keys `cr_activity_*`). */
class LiveUpdateParseTest {
  private val full = mapOf(
    "cr_activity_id" to "order_8392",
    "cr_activity_type" to "delivery",
    "cr_activity_op" to "update",
    "cr_activity_title" to "On the way",
    "cr_activity_body" to "7 min",
    "cr_activity_progress" to "70",
    "cr_activity_status_text" to "Arriving soon",
    // The server also sends the data-only hints, so older SDKs ignore the message.
    "cr_title" to "On the way",
    "cr_body" to "7 min",
    "cr_data_only" to "true",
  )

  @Test
  fun parsesAFullMessage() {
    val m = LiveUpdate.fromData(full)!!
    assertEquals("order_8392", m.activityId)
    assertEquals("delivery", m.activityType)
    assertEquals(LiveUpdate.Op.UPDATE, m.op)
    assertEquals("On the way", m.title)
    assertEquals("7 min", m.body)
    assertEquals(70, m.progress)
    assertEquals("Arriving soon", m.statusText)
  }

  @Test
  fun parsesEachOperation() {
    assertEquals(LiveUpdate.Op.START, LiveUpdate.fromData(full + ("cr_activity_op" to "start"))!!.op)
    assertEquals(LiveUpdate.Op.END, LiveUpdate.fromData(full + ("cr_activity_op" to "end"))!!.op)
  }

  @Test
  fun optionalFieldsMayBeAbsent() {
    val m = LiveUpdate.fromData(
      mapOf("cr_activity_id" to "a", "cr_activity_type" to "t", "cr_activity_op" to "end"),
    )!!
    assertNull(m.title)
    assertNull(m.body)
    assertNull(m.progress)
    assertNull(m.statusText)
  }

  @Test
  fun anOrdinaryPushIsNotALiveUpdate() {
    assertNull(LiveUpdate.fromData(mapOf("cr_title" to "Hi", "cr_body" to "There", "order_id" to "1")))
    assertNull(LiveUpdate.fromData(emptyMap()))
    assertNull(LiveUpdate.fromData(null))
  }

  @Test
  fun aMessageMissingItsIdOrOperationIsIgnored() {
    assertNull(LiveUpdate.fromData(full - "cr_activity_id"))
    assertNull(LiveUpdate.fromData(full - "cr_activity_op"))
    assertNull(LiveUpdate.fromData(full + ("cr_activity_id" to "  ")))
    assertNull(LiveUpdate.fromData(full + ("cr_activity_op" to "explode")))
  }

  @Test
  fun progressMustBeAWholeNumberFromZeroToOneHundred() {
    assertEquals(0, LiveUpdate.fromData(full + ("cr_activity_progress" to "0"))!!.progress)
    assertEquals(100, LiveUpdate.fromData(full + ("cr_activity_progress" to "100"))!!.progress)
    for (bad in listOf("101", "-1", "7.5", "1e1", "abc", "")) {
      assertNull("progress $bad", LiveUpdate.fromData(full + ("cr_activity_progress" to bad))!!.progress)
    }
  }

  @Test
  fun blankTextIsTreatedAsAbsent() {
    val m = LiveUpdate.fromData(full + ("cr_activity_title" to " ") + ("cr_activity_status_text" to ""))!!
    assertNull(m.title)
    assertNull(m.statusText)
  }
}
