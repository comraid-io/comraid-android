package com.comraid.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Acceptance seam: root README method reference vs gap matrix §3 (Android public ComRaid).
 * Depth B: each public method has a signature-style heading; Errors + docs cross-link;
 * mobileSubscriptionSession documented as HTTP-only (no SDK method).
 */
class ReadmeMethodReferenceParityTest {
  private val readme by lazy {
    locate("README.md", "../README.md").readText()
  }

  @Test
  fun coversPublicComRaidMethodsFromGapMatrix() {
    val methods = listOf(
      "configure",
      "capabilities",
      "requestPermission",
      "onRequestPermissionsResult",
      "register",
      "onPushTokenRefresh",
      "login",
      "logout",
      "optOut",
      "optIn",
      "setDeviceProperties",
      "setAttributes",
      "acknowledgeDelivered",
      "acknowledgeOpened",
      "flushAcks",
      "schedule",
      "startTimer",
      "cancel",
      "pendingSchedules",
    )
    val missing = methods.filterNot { method ->
      readme.contains("### `$method") || readme.contains("### `ComRaid.$method")
    }
    assertTrue(
      "README Depth B must document each public method (gap matrix §3). Missing: $missing",
      missing.isEmpty(),
    )
  }

  @Test
  fun documentsErrorsAndHttpOnlySession() {
    assertTrue(
      "README must include an Errors subsection for ComRaidException",
      readme.contains("## Errors") && readme.contains("ComRaidException"),
    )
    assertTrue(
      "README must note mobileSubscriptionSession is HTTP-only (no SDK method)",
      readme.contains("mobileSubscriptionSession") &&
        (readme.contains("HTTP-only") || readme.contains("no SDK")),
    )
  }

  @Test
  fun crossLinksCanonicalDocsAndroidPage() {
    assertTrue(
      "README must cross-link https://docs.comraid.io/sdks/android",
      readme.contains("https://docs.comraid.io/sdks/android"),
    )
  }

  @Test
  fun includesClusteredLifecycleExamples() {
    val markers = listOf(
      "```kotlin",
      "configure",
      "login",
      "schedule",
      "acknowledgeDelivered",
    )
    markers.forEach { marker ->
      assertTrue("clustered examples should mention $marker", readme.contains(marker))
    }
  }

  private fun locate(vararg candidates: String): File =
    candidates.map(::File).firstOrNull { it.isFile }
      ?: error("could not locate ${candidates.joinToString()} from ${System.getProperty("user.dir")}")
}
