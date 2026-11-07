plugins {
  id("com.android.library")
  id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "com.comraid"
version = "0.1.0"

val signingInMemoryKey: Provider<String> =
  providers
    .gradleProperty("signingInMemoryKey")
    .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey"))

android {
  namespace = "com.comraid.android"
  compileSdk = 36

  defaultConfig {
    minSdk = 23
    consumerProguardFiles("consumer-rules.pro")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions {
    unitTests.isIncludeAndroidResources = false
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.18.0")
  // Stable EncryptedSharedPreferences (CE-03 storage choice). Avoid 1.1.0-alpha*.
  implementation("androidx.security:security-crypto:1.0.0")
  implementation("androidx.work:work-runtime-ktx:2.10.5")
  // Host apps supply Firebase Messaging at runtime; SDK compiles against it optionally.
  compileOnly("com.google.firebase:firebase-messaging:24.1.1")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20250517")
  testImplementation("org.robolectric:robolectric:4.16")
}

mavenPublishing {
  // Coordinates kept as com.comraid:comraid:0.1.0 (groupId / artifactId / version).
  coordinates("com.comraid", "comraid", version.toString())

  publishToMavenCentral()

  // Sign when a key is available (env / ~/.gradle/gradle.properties). Never commit keys.
  if (signingInMemoryKey.isPresent) {
    signAllPublications()
  }

  pom {
    name.set("comraid")
    description.set("ComRaid Android SDK for push Subscriptions, remote display, and delivery ACKs")
    inceptionYear.set("2026")
    url.set("https://github.com/comraid-io/comraid-android")
    licenses {
      license {
        name.set("MIT License")
        url.set("https://opensource.org/licenses/MIT")
        distribution.set("repo")
      }
    }
    developers {
      developer {
        id.set("comraid")
        name.set("ComRaid")
        url.set("https://comraid.io")
      }
    }
    scm {
      url.set("https://github.com/comraid-io/comraid-android")
      connection.set("scm:git:git://github.com/comraid-io/comraid-android.git")
      developerConnection.set("scm:git:ssh://git@github.com/comraid-io/comraid-android.git")
    }
  }
}

publishing {
  repositories {
    maven {
      name = "localTest"
      url = uri(layout.buildDirectory.dir("repo"))
    }
  }
}

/**
 * Publishes the release publication into build/repo and asserts the Central-shaped
 * layout (AAR + sources + javadoc + POM metadata). Signing is validated when a key is set.
 */
tasks.register("validateMavenCentralBundle") {
  group = "publishing"
  description =
    "Dry-run Central bundle: publish to build/repo and check required artifacts + POM fields"
  dependsOn("publishMavenPublicationToLocalTestRepository")
  doLast {
    val versionDir =
      layout.buildDirectory.dir("repo/com/comraid/comraid/$version").get().asFile
    require(versionDir.isDirectory) { "Missing published version dir: $versionDir" }

    val required =
      listOf(
        "comraid-$version.aar",
        "comraid-$version-sources.jar",
        "comraid-$version-javadoc.jar",
        "comraid-$version.pom",
      )
    required.forEach { name ->
      val file = File(versionDir, name)
      require(file.isFile && file.length() > 0) { "Missing or empty artifact: $file" }
    }

    val pom = File(versionDir, "comraid-$version.pom").readText()
    listOf(
      "<url>https://github.com/comraid-io/comraid-android</url>",
      "<name>MIT License</name>",
      "<connection>scm:git:git://github.com/comraid-io/comraid-android.git</connection>",
      "<id>comraid</id>",
      "<groupId>com.comraid</groupId>",
      "<artifactId>comraid</artifactId>",
    ).forEach { needle ->
      require(pom.contains(needle)) { "POM missing required metadata: $needle" }
    }

    if (signingInMemoryKey.isPresent) {
      listOf(".aar", "-sources.jar", "-javadoc.jar", ".pom").forEach { suffix ->
        val asc = File(versionDir, "comraid-$version$suffix.asc")
        require(asc.isFile && asc.length() > 0) { "Missing signature: $asc" }
      }
    }

    logger.lifecycle("Central bundle validation OK: $versionDir")
  }
}
