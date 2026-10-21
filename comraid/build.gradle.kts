plugins {
  id("com.android.library")
  `maven-publish`
}

group = "com.comraid"
version = "0.1.0-SNAPSHOT"

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

  publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
  implementation("androidx.core:core-ktx:1.18.0")
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  // Host apps supply Firebase Messaging at runtime; SDK compiles against it optionally.
  compileOnly("com.google.firebase:firebase-messaging:24.1.1")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20250517")
}

publishing {
  repositories {
    maven {
      name = "localTest"
      url = uri(layout.buildDirectory.dir("repo"))
    }
  }
  publications {
    // Legacy coordinates (publish alias until contract).
    register<MavenPublication>("release") {
      afterEvaluate { from(components["release"]) }
      groupId = "com.comraide"
      artifactId = "comraid"
    }
    // Primary coordinates. Cannot call from(components["release"]) twice, so
    // publish the same AAR under com.comraid:comraid.
    register<MavenPublication>("comraidCanonical") {
      groupId = "com.comraid"
      artifactId = "comraid"
      version = project.version.toString()
      afterEvaluate {
        artifact(tasks.named("bundleReleaseAar")) {
          extension = "aar"
        }
      }
      pom {
        name.set("comraid")
        description.set("ComRaid Android SDK")
        packaging = "aar"
      }
    }
  }
}
