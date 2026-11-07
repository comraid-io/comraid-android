package com.comraid.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Publish-readiness contracts for Maven Central dry-run (ticket 12).
 * Bundle layout is validated by `:comraid:validateMavenCentralBundle`.
 */
class PublishReadinessTest {
  @Test
  fun rootLicenseIsMit() {
    val license = locate("LICENSE", "../LICENSE")
    val text = license.readText()
    assertTrue(text.startsWith("MIT License"))
    assertTrue(text.contains("Copyright (c) 2026 ComRaid"))
  }

  @Test
  fun buildUsesStableSecurityCryptoNotAlpha() {
    val buildScript = locate("comraid/build.gradle.kts", "build.gradle.kts")
    val text = buildScript.readText()
    assertTrue(
      "must depend on stable androidx.security:security-crypto:1.0.0",
      text.contains("androidx.security:security-crypto:1.0.0"),
    )
    assertFalse("must not pin security-crypto alpha", text.contains("security-crypto:1.1.0-alpha"))
    assertFalse("must not contain -alpha dependency pins", Regex("""implementation\("[^"]+-alpha[^"]*"\)""").containsMatchIn(text))
  }

  @Test
  fun centralPortalPublishingAndPomMetadataConfigured() {
    val buildScript = locate("comraid/build.gradle.kts", "build.gradle.kts")
    val text = buildScript.readText()
    assertTrue(text.contains("publishToMavenCentral()"))
    assertTrue(text.contains("com.vanniktech.maven.publish"))
    assertTrue(text.contains("validateMavenCentralBundle"))
    assertTrue(text.contains("https://github.com/comraid-io/comraid-android"))
    assertTrue(text.contains("MIT License"))
    assertTrue(text.contains("scm:git:git://github.com/comraid-io/comraid-android.git"))
    assertTrue(text.contains("signingInMemoryKey"))
  }

  private fun locate(vararg candidates: String): File =
    candidates.map(::File).firstOrNull { it.isFile }
      ?: error("could not locate ${candidates.joinToString()} from ${System.getProperty("user.dir")}")
}
