plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.vaultledger.pfxq"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

  }

  signingConfigs {
    create("release") {
      // Falls back to the debug keystore when no upload key is configured, so
      // `assembleRelease` works on a fresh clone. A release built this way is
      // measurable and installable but obviously not publishable — the real key
      // comes from the environment on the machine that actually ships it.
      val configuredKeystore = System.getenv("KEYSTORE_PATH")?.let { file(it) }
        ?: file("${rootDir}/my-upload-key.jks").takeIf { it.exists() }

      if (configuredKeystore != null) {
        storeFile = configuredKeystore
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      } else {
        storeFile = file("${rootDir}/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")

      // T5.4 — libsqlcipher.so is 5.5 MB on arm64 and 3.8 MB on armeabi-v7a
      // because SQLCipher statically links OpenSSL's libcrypto. Carrying the
      // emulator ABIs as well would put a universal APK over the 30 MB budget on
      // architectures no shipping phone uses.
      ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
      // x86_64 for the emulator, arm64 for a real device.
      ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }

  // T3.4 — the exported schema is what migration tests diff against, so it is a
  // build input, not a build artifact. Commit app/schemas/.
  ksp { arg("room.schemaLocation", "$projectDir/schemas") }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// T1.1 / F5.4 — This app declares no INTERNET permission and links no networking
// library. Do not add Firebase, OkHttp, Retrofit, or any HTTP client here: their
// AAR manifests merge android.permission.INTERNET into the APK, which silently
// breaks the zero-egress guarantee. NetworkEgressGuardTest fails the build if
// that ever happens again.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.sqlite)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // T3.1 — SQLCipher. Note that this replaces the platform SQLite binding, so
  // `adb shell run-as ... sqlite3 vault_ledger.db` no longer works. That is the
  // point; use the F5.1 export or an instrumented test to inspect data.
  implementation(libs.sqlcipher.android)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.room.testing)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
