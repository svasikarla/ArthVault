plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

android {
  namespace = "com.arthvault"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    // Changed from the generated "com.aistudio.vaultledger.pfxq" before first real
    // use. An applicationId is permanent in practice: Android treats a change as a
    // different app, so anyone with the old build installed must uninstall it, and
    // the encrypted ledger on that device goes with it. That cost only ever grows.
    applicationId = "com.arthvault.ledger"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

  }

  // The upload key, if this machine has one. `null` is a meaningful value here:
  // it means the release APK goes out unsigned.
  val uploadKeystore = System.getenv("KEYSTORE_PATH")?.let { file(it) }
    ?: file("${rootDir}/my-upload-key.jks").takeIf { it.exists() }

  signingConfigs {
    if (uploadKeystore != null) {
      create("release") {
        storeFile = uploadKeystore
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
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
      // T5.4 — see proguard-rules.pro. Every -keep in there is load-bearing:
      // SQLCipher resolves its Java side by name over JNI, and Room reaches its
      // generated *_Impl the same way. Both failures would surface at unlock,
      // not at build time, which is why the rules file explains each one.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

      // No upload key means no signature — not the debug signature.
      //
      // This used to fall back to debug.keystore so that `assembleRelease` always
      // produced an installable APK. That is the wrong kind of convenient: the debug
      // key is checked into this repository and its password is the string
      // "android", so a release signed with it can be replaced by an update from
      // anyone who has cloned the project. The failure was also silent — the APK
      // installed and ran, and nothing distinguished it from a properly signed one.
      //
      // Unsigned fails at install time instead, which is loud, harmless, and
      // impossible to ship by accident. Size and shrink measurement still work.
      signingConfig = signingConfigs.findByName("release")
      if (signingConfig == null) {
        logger.warn(
          "arth-vault: no upload key (set KEYSTORE_PATH, or drop my-upload-key.jks " +
            "in the project root) — the release APK will be UNSIGNED and will not install."
        )
      }

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
