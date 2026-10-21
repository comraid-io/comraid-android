package com.comraide.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Expand-phase check: both Maven groupIds must be declared for dual publish.
 * Resolves the module build script relative to the JVM user.dir (Gradle sets it to the module dir).
 */
class PackageIdsExpandTest {
  @Test
  fun dualMavenCoordinatesAreDeclared() {
    val buildScript =
      sequenceOf(
          File("build.gradle.kts"),
          File("comraid/build.gradle.kts"),
        )
        .firstOrNull { it.isFile }
        ?: error("could not locate comraid/build.gradle.kts from ${System.getProperty("user.dir")}")

    val text = buildScript.readText()
    assertTrue(
      "legacy groupId com.comraide must remain",
      text.contains("\"com.comraide\"") || text.contains("groupId = \"com.comraide\""),
    )
    assertTrue(
      "new groupId com.comraid must be declared for expand",
      text.contains("groupId = \"com.comraid\""),
    )
    assertTrue(
      "artifactId comraid must remain",
      text.contains("artifactId = \"comraid\""),
    )
  }
}
