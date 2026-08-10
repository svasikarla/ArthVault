---
name: android-tech-stack
description: Pinned library, tooling, and Google Play version matrix for this Kotlin + Jetpack Compose project (verified June 2026). Use whenever choosing, generating, or reviewing dependencies, Gradle/AGP config, compileSdk/targetSdk, or release target API — this is the single source of truth for versions. Re-verify before each new project because these change roughly quarterly.
---

# Android Tech Stack (single source of truth)

Use ONLY these versions and choices unless the human explicitly overrides them.
All facts verified June 2026; re-verify at project start and before each release.

## Platform & Google Play
- Latest Android OS: Android 17 (API 37, "Cinnamon Bun"), stable 2026-06-16.
- Play target API bar: **API 36 (Android 16)** for new apps and updates by 2026-08-31
  (Wear OS / Android TV one tier lower at API 35). Plan to move to API 37.
- compileSdk = 36, targetSdk = 36 now; move to 37 once toolchain supports it
  (Compose 1.12 will require compileSdk 37 + AGP 9).
- minSdk: choose per audience; 24+ is a common pragmatic floor.
- Distribution: AAB mandatory; Play App Signing mandatory for new apps.

## Build toolchain
| Tool | Version | Note |
|---|---|---|
| Kotlin | 2.3.x | AGP 9 bundles KGP. |
| AGP (Android Gradle Plugin) | 9.3.0 | Built-in Kotlin; new DSL default; legacy DSL removed in AGP 10. |
| Gradle | 9.x | Hard requirement for AGP 9. |
| KSP | 2.3.9 (KSP2) | Decoupled from Kotlin version; required by Room 3. Do NOT use KAPT. |
| Android Studio | Quail (2026.1.1) | Stable. |
| Compose Compiler | Kotlin Compose Gradle plugin (`org.jetbrains.kotlin.plugin.compose`), versioned to Kotlin 2.3.x | Replaces legacy `kotlinCompilerExtensionVersion`. |

## Core libraries
| Concern | Library | Version | One-line rationale |
|---|---|---|---|
| UI | Jetpack Compose (BOM) | 2026.04.01 (core 1.11.0) | Declarative, Google's primary toolkit. |
| Design system | androidx.compose.material3 | 1.4.x | Native M3 + dynamic color + adaptive. |
| Adaptive | material3-adaptive / navigation-suite | current stable in BOM | WindowSizeClass + canonical layouts. |
| Async | kotlinx.coroutines | 1.11.0 | Structured concurrency + Flow. |
| Serialization | kotlinx.serialization (json) | 1.11.0 | Kotlin-native, reflection-free. |
| Networking | Retrofit | 3.0.0 | De-facto REST client (Kotlin rewrite). |
| HTTP | OkHttp | 5.4.0 | Add explicitly; Retrofit 3 bundles OkHttp 4.12 transitively. |
| Local DB | Room | 2.8.x stable | Compile-time-verified SQL + Flow. (Room 3 / androidx.room3 is alpha, KMP.) |
| Prefs/key-value | DataStore | 1.2.1 | Async, type-safe; replaces SharedPreferences. |
| Image loading | Coil | 3.5.0 | Compose-native, coroutine-based. |
| Background work | WorkManager | 2.11.x | Guaranteed deferrable work. |
| Lifecycle | androidx.lifecycle | 2.9.x | collectAsStateWithLifecycle. |
| DI (core) | com.google.dagger:hilt-android | 2.59.2 | Compile-time DI, Google standard. |
| DI (Compose nav) | androidx.hilt:hilt-navigation-compose | 1.3.0 | hiltViewModel() in Compose. |
| Navigation | Navigation Compose (type-safe) | 2.9.x | Type-safe routes, nested graphs, deep links. |

## Testing libraries
| Concern | Library | Version |
|---|---|---|
| Unit | JUnit + kotlinx-coroutines-test | current |
| Flow testing | Turbine | 1.2.1 |
| Mocking | MockK | 1.14.11 |
| Compose UI test | androidx.compose.ui:ui-test-junit4 | from BOM |
| JVM Android | Robolectric | current |
| Screenshot | Paparazzi | 1.3.2 (2.0 alpha) — or Compose preview screenshot testing |
| E2E | Maestro | latest stable (verify on GitHub releases before pinning) |

## Mandatory build setup
- Use a version catalog: `gradle/libs.versions.toml` for all versions/plugins.
- Use `build-logic` convention plugins (an included build) to centralize
  compileSdk/Kotlin/Compose/common deps so module build files stay tiny.
- Apply the Compose Compiler Gradle plugin; enable KSP (not KAPT).
- AGP 9 has built-in Kotlin support — no separate `kotlin-android` apply needed.

## When to deviate (decision thresholds)
- Need code sharing with iOS/web (KMP): switch DI to Koin, networking to Ktor,
  adopt Room 3 (androidx.room3) or SQLDelight, kotlinx.serialization stays.
- Tiny single-screen utility: drop the domain layer, skip modularization.
- No backend: drop Retrofit/OkHttp/serialization; center on Room + DataStore.

## Pitfalls
- Don't pin versions inline in module build files — use the catalog.
- Don't use KAPT (KSP1 deprecated, incompatible with Kotlin 2.3+).
- Don't ship compileSdk/targetSdk below the live Play bar.
- Several successors are in flight (Room 3, Navigation3, Material3 1.5,
  Paparazzi 2.0, DataStore 1.3) — stay on stable unless you accept alpha risk.
