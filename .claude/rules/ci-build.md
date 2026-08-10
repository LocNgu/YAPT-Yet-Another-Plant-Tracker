---
description: Build toolchain, Detekt, CI job graph, and cloud/session build setup
paths:
  - "**/*.gradle.kts"
  - "gradle.properties"
  - "gradle/**/*"
  - "version.properties"
  - ".github/**/*"
  - "config/detekt/**/*"
  - "scripts/**/*"
---

# CI / Build rules

## Toolchain (AGP 9.3.1 / Gradle 9.7.0 / Kotlin plugins 2.4.10 / KSP 2.3.11)
- Compose BOM 2026.06.01 · compileSdk 37 · targetSdk 35 · minSdk 26.
- **Kotlin + KSP move together** — KSP2 uses Kotlin-aligned versioning (KSP `2.3.10` = Kotlin `2.3.10`).
  Kotlin 2.4.x is not adoptable until KSP ships a 2.4 release (why a grouped Kotlin-2.4 + KSP-2.3 Dependabot PR
  can never go green).
- **AGP 9 provides Kotlin compilation itself** — the standalone `org.jetbrains.kotlin.android` plugin is NOT applied
  and AGP 9 errors if it is present. Do not re-add it. Compose/serialization/KSP plugins stay, pinned to 2.3.10.
- `kotlinOptions { jvmTarget }` was migrated to top-level `kotlin { compilerOptions { jvmTarget.set(JVM_17) } }`.
- `gradle-wrapper.properties` distribution **and** the four `gradle-version` pins in `android.yml` must match
  (`setup-gradle` overrides the wrapper).
- `android.onlyEnableUnitTestForTheTestedBuildType=false` in `gradle.properties` restores pre-AGP-9 behaviour so
  `testReleaseUnitTest` exists for the release job. It's global, so `./gradlew test` runs both debug + release
  suites (#496).

## Detekt (#85, #463)
- `Run Detekt` in the `test` job fails PRs on new violations. Config: `config/detekt/detekt.yml`
  (`buildUponDefaultConfig`; formatting `maxLineLength` 120). `FunctionNaming` + `MagicNumber` are active but
  `excludes: ['**/ui/**','**/test/**','**/androidTest/**']` (skip `@Composable`/test naming + Compose dp/sp literals).
- Frozen smells in `config/detekt/baseline.xml` — regenerate with `./gradlew detektBaseline` ONLY when intentionally
  accepting debt. `./gradlew detekt` locally; `autoCorrect = true` auto-fixes formatting (CI never auto-corrects).

## CI job graph (#84, #87)
`test` (Detekt + unit tests + lintDebug) gates both `build` (debug APK) and `release`; release also runs
`testReleaseUnitTest` + `lintRelease`. Instrumented tests run on PRs via path filter; concurrency group cancels
stacked runs. Push to `main` auto-creates a signed-APK GitHub Release (`--target SHA` anchors the tag).

## Release build (#4)
`isMinifyEnabled = true`, `isShrinkResources = true` on the release build type. ProGuard rules keep WorkManager
workers and Room DAOs (both reached via reflection) from being stripped/renamed.

## Cloud / in-session builds (#419)
Enablement is environment config, not repo: allowlist `dl.google.com`, set `ANDROID_HOME=/opt/android-sdk`, run
`scripts/cloud-setup.sh` as setup. It installs the SDK and seeds the wrapper dist from the pre-installed Gradle.
**Only works if the pre-installed Gradle is 9.x** (AGP 9 needs it); an image still on Gradle 8.x fails locally. CI is
unaffected (`setup-gradle` downloads pinned 9.6.1) and remains the authoritative gate. Instrumented tests still need
CI's emulator.
