plugins {
  id("com.android.library")
  `maven-publish`
}

group = "com.comraide"
version = "0.1.0-SNAPSHOT"

android {
  namespace = "com.comraide.android"
  compileSdk = 36

  defaultConfig {
    minSdk = 23
    consumerProguardFiles("consumer-rules.pro")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
  implementation("androidx.core:core-ktx:1.18.0")
}

publishing {
  repositories {
    maven {
      name = "localTest"
      url = uri(layout.buildDirectory.dir("repo"))
    }
  }
  publications {
    register<MavenPublication>("release") {
      afterEvaluate { from(components["release"]) }
      artifactId = "comraid"
    }
  }
}
