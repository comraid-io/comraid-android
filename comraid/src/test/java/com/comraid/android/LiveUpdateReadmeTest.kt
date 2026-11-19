package com.comraid.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The README must say what a Live update needs and does, not just list the two methods. */
class LiveUpdateReadmeTest {
  private val readme by lazy {
    listOf("README.md", "../README.md").map(::File).first { it.isFile }.readText()
  }

  @Test
  fun hasALiveUpdatesSection() {
    assertTrue(readme.contains("### Live updates"))
  }

  @Test
  fun saysWhatAHostNeedsToKnow() {
    for (fact in listOf(
      "Android 16",
      "POST_NOTIFICATIONS",
      "ComraidMessagingService",
      "super.onMessageReceived",
      "Swiping it away",
      "8 hours",
    )) {
      assertTrue("README must mention: $fact", readme.contains(fact))
    }
  }
}
