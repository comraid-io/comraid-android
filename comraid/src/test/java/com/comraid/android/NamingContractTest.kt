package com.comraid.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract: shipped Android SDK paths must not contain the misspelling
 * comraide / Comraide / com.comraide. Intentional mentions in this test file
 * are the negative assertions themselves.
 */
class NamingContractTest {
  @Test
  fun mavenAndNamespaceAreComraidOnly() {
    val buildScript = locate("comraid/build.gradle.kts", "build.gradle.kts")
    val text = buildScript.readText()
    assertTrue("project group must be com.comraid", text.contains("group = \"com.comraid\""))
    assertTrue("namespace must be com.comraid.android", text.contains("namespace = \"com.comraid.android\""))
    assertTrue("canonical groupId com.comraid must remain", text.contains("groupId = \"com.comraid\""))
    assertTrue("artifactId comraid must remain", text.contains("artifactId = \"comraid\""))
    assertFalse("legacy groupId com.comraide must be removed", text.contains("com.comraide"))
    assertFalse("legacy misspelling comraide must be removed", text.contains("comraide"))
  }

  @Test
  fun sourcesLiveUnderComComraidPackage() {
    val main = locate(
      "comraid/src/main/java/com/comraid/android/ComRaid.kt",
      "src/main/java/com/comraid/android/ComRaid.kt",
    )
    assertTrue(main.readText().contains("package com.comraid.android"))
    assertTrue(main.readText().contains("object ComRaid"))
  }

  @Test
  fun publicTypeAliasesFileIsRemoved() {
    val aliases = sequenceOf(
      File("comraid/src/main/java/com/comraid/android/Aliases.kt"),
      File("src/main/java/com/comraid/android/Aliases.kt"),
    )
    assertFalse(
      "Aliases.kt must be deleted under the naming contract",
      aliases.any { it.isFile },
    )
  }

  @Test
  fun shippedMainSourcesContainNoComraideMisspelling() {
    val mainRoot = locateDir(
      "comraid/src/main",
      "src/main",
    )
    val buildScript = locate("comraid/build.gradle.kts", "build.gradle.kts")
    val offenders = mutableListOf<String>()
    sequenceOf(buildScript).plus(mainRoot.walkTopDown().filter { it.isFile }).forEach { file ->
      val text = file.readText()
      for (needle in listOf("comraide", "Comraide", "com.comraide")) {
        if (text.contains(needle)) {
          offenders += "${file.relativeTo(mainRoot.parentFile ?: mainRoot)}:$needle"
        }
      }
    }
    assertTrue(
      "shipped paths must not contain comraide misspellings: $offenders",
      offenders.isEmpty(),
    )
  }

  private fun locate(vararg candidates: String): File =
    candidates.map(::File).firstOrNull { it.isFile }
      ?: error("could not locate ${candidates.joinToString()} from ${System.getProperty("user.dir")}")

  private fun locateDir(vararg candidates: String): File =
    candidates.map(::File).firstOrNull { it.isDirectory }
      ?: error("could not locate dir ${candidates.joinToString()} from ${System.getProperty("user.dir")}")
}
