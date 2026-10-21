package com.comraid.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Migrate-phase check: primary Maven/Android identifiers are locked ComRaid forms;
 * legacy com.comraide may remain as an unused publish alias.
 */
class PackageIdsMigrateTest {
  @Test
  fun primaryMavenAndNamespaceAreComraidLockedForms() {
    val buildScript =
      sequenceOf(
          File("build.gradle.kts"),
          File("comraid/build.gradle.kts"),
        )
        .firstOrNull { it.isFile }
        ?: error("could not locate comraid/build.gradle.kts from ${System.getProperty("user.dir")}")

    val text = buildScript.readText()
    assertTrue("project group must be com.comraid", text.contains("group = \"com.comraid\""))
    assertTrue("namespace must be com.comraid.android", text.contains("namespace = \"com.comraid.android\""))
    assertTrue("canonical groupId com.comraid must remain", text.contains("groupId = \"com.comraid\""))
    assertTrue(
      "legacy groupId com.comraide may remain as publish alias",
      text.contains("groupId = \"com.comraide\""),
    )
    assertTrue("artifactId comraid must remain", text.contains("artifactId = \"comraid\""))
  }

  @Test
  fun sourcesLiveUnderComComraidPackage() {
    val main =
      sequenceOf(
          File("src/main/java/com/comraid/android/ComRaid.kt"),
          File("comraid/src/main/java/com/comraid/android/ComRaid.kt"),
        )
        .firstOrNull { it.isFile }
        ?: error("ComRaid.kt missing under com/comraid/android from ${System.getProperty("user.dir")}")
    assertTrue(main.readText().contains("package com.comraid.android"))
    assertTrue(main.readText().contains("object ComRaid"))
  }

  @Test
  fun publicTypeAliasesKeepLegacyComraidNames() {
    assertEquals(ComRaid::class.java, Comraid::class.java)
  }
}
