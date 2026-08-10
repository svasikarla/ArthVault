---
name: android-security-release-checklist
description: Security hardening and Google Play release-readiness checklist for this project — secure storage, network security and certificate pinning, R8 obfuscation, secrets, Play Integrity, plus the AAB/signing/target-API/data-safety release gate (verified June 2026). Use when implementing security, preparing a release, or running the final pre-submission review.
---

# Security & Release Readiness

## Secure storage
- Sensitive prefs: Jetpack Security `EncryptedSharedPreferences` / encrypted DataStore
  (note: classic Jetpack Security crypto is deprecated — verify current replacement guidance at release time).
- Keys via Android Keystore (hardware-backed, `setUserAuthenticationRequired` for high-value keys).
- DB encryption: SQLCipher for Room if data is sensitive.
- NEVER store tokens/keys in plain SharedPreferences or in source.

## Network
- HTTPS only; provide a `network_security_config.xml`, disable cleartext.
- Certificate pinning via OkHttp `CertificatePinner` (pin backup keys; have a rotation plan).
- No secrets in the APK/AAB — fetch at runtime or inject at build time; assume client binaries are readable.

## Code shrinking & obfuscation
- Release build: `isMinifyEnabled = true`, `isShrinkResources = true` (R8).
- Maintain `proguard-rules.pro`; keep reflection/serialization models as needed.
- Upload mapping.txt to Play for deobfuscated crash reports.

## Secrets management
- Keep keys out of VCS; use `local.properties`/CI secrets + Gradle `BuildConfig` injection or Secrets Gradle Plugin.
- Rotate any key that ever touched source control.

## Integrity & abuse
- Play Integrity API to attest genuine app/device for sensitive actions.
- Validate all server-trust decisions server-side; never trust the client alone.

## Performance gates (ship-blockers if regressed)
- Ship a **Baseline Profile** (Macrobenchmark generated) — large real-world startup/jank win.
- No leaks (LeakCanary clean on core flows); strict-mode clean on main thread.
- Recomposition profiled on heavy screens (Layout Inspector / composition tracing).

## Google Play release gate (verify each item at submission, June 2026 baseline)
- [ ] Build is an **Android App Bundle (.aab)** (APK not accepted for new apps).
- [ ] **Play App Signing** enrolled.
- [ ] `targetSdk` meets the current bar: **API 36** for new apps & updates by 2026-08-31
      (plan API 37). compileSdk 36 (move to 37 when toolchain ready).
- [ ] `versionCode` incremented; `versionName` set.
- [ ] Release build: minify + resource shrink on; mapping.txt uploaded.
- [ ] **Data safety form** completed and matches actual SDK/data behavior.
- [ ] Declared permissions justified; sensitive permissions have Play declarations.
- [ ] Privacy policy URL set; required disclosures present.
- [ ] Store listing: title, short/full description, screenshots (phone + tablet/foldable if supported), feature graphic.
- [ ] Content rating questionnaire completed.
- [ ] Tested via internal/closed track before production; staged rollout planned.
- [ ] Crash reporting wired (Crashlytics / Play vitals) with deobfuscation.

## Pitfalls
- Data-safety form not matching real behavior (a common rejection/enforcement cause).
- Forgetting mapping.txt — unreadable production stack traces.
- Cert pinning with no backup pin/rotation — outage when the cert rotates.
- Missing tablet/foldable screenshots while claiming large-screen support.
- Discovering the target-API bar at submission instead of at planning.
