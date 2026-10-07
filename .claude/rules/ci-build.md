---
description: Build toolchain, Detekt, CI job graph, Robolectric test application, and cloud/session build setup
paths:
  - "**/*.gradle.kts"
  - "gradle.properties"
  - "gradle/**/*"
  - "version.properties"
  - ".github/**/*"
  - "config/detekt/**/*"
  - "scripts/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/YaptApplication.kt"
  - "app/src/test/kotlin/com/yapt/planttracker/TestYaptApplication*.kt"
  - "app/src/test/resources/**/*"
---

# CI / Build rules

## Toolchain (AGP 9.4.1 / Gradle 9.8.0 / Kotlin plugins 2.4.20 / KSP 2.3.12)
- Compose BOM 2026.09.00 · compileSdk 37 · targetSdk 35 · minSdk 26.
- **Kotlin and KSP versions needn't match.** KSP has its own release line (the `<kotlin>-<ksp>` scheme is legacy), so a Dependabot PR with different numbers isn't self-evidently broken; validate through CI. The KotlinX/MockK ↔ Kotlin stdlib coupling noted in `.github/dependabot.yml` is real.
- **AGP 9 compiles Kotlin itself.** Never re-add `org.jetbrains.kotlin.android` (AGP 9 errors). Compose/serialization plugins stay at 2.4.20. Use `kotlin { compilerOptions { jvmTarget.set(JVM_17) } }`.
- **`gradle-wrapper.properties` is the only Gradle version source** (#726). CI uses `gradle-version: wrapper`; never hardcode one there. Dependabot doesn't bump the wrapper, so do it by hand.
- `android.onlyEnableUnitTestForTheTestedBuildType=false` keeps `testReleaseUnitTest` for the release job, so `./gradlew test` runs both suites (#496).
- Release build: `isMinifyEnabled`/`isShrinkResources` on; ProGuard keeps WorkManager workers and Room DAOs (reflection) (#4).

## Detekt (#85, #463)
- `Run Detekt` in the `test` job fails PRs on new violations. Config `config/detekt/detekt.yml` (`buildUponDefaultConfig`, `maxLineLength` 120). `FunctionNaming`/`MagicNumber` exclude `ui/`, `test/` and `androidTest/`.
- `config/detekt/baseline.xml` freezes old smells. Regenerate it (`./gradlew detektBaseline`) only when deliberately accepting debt. Use `autoCorrect = true` locally only; CI never auto-corrects.
- Common splits: `TooManyFunctions` (move helpers to a sibling file) and `LongParameterList` (bundle callbacks in a data class).

## CI job graph (#84, #87)
- `test` (Detekt + unit tests + `lintDebug`) gates `build` (debug APK) and `release` (`testReleaseUnitTest` + `lintRelease`).
- PRs are path-filtered: instrumented tests run only for relevant paths, and docs-only PRs skip the Android jobs (any `app/**` or workflow change still runs them). A concurrency group cancels stacked runs.
- A push to `main` creates a signed-APK GitHub Release.

## Robolectric runs `TestYaptApplication` (#757)
- **What it is:** the production application with `launchAppStartWork()` (the `onCreate` coroutine: default reminder time + `SeasonalGraduationFixup`) overridden to a no-op. That override is the only reason `YaptApplication` and the method are `open`.
- **Why:** Robolectric builds an application per test method, but the Room DB and the DataStore delegate are process-wide. The fire-and-forget launch raced test fixtures and rewrote `wateringBaseIntervalDays`.
- **Two routes select it; the class name is load-bearing.**
  - Robolectric resolves `Test` + the manifest application's simple name before the manifest class.
  - `app/src/test/resources/robolectric.properties` registers it explicitly.
  - Rename the class and you silently lose the first route, so keep the file in step.
- **Rules:**
  - New app-start work goes inside `launchAppStartWork()` and gets its own direct test.
  - Never `@Config(application = YaptApplication::class)`.
  - A test needing app-start behaviour calls the underlying function.
  - `TestYaptApplicationTest` guards which application is instantiated.

## Pre-push gate and fast iteration (#684, #807)
- **Before every push that opens or updates a PR, fix rounds included:** `./gradlew detekt lintDebug compileDebugKotlin compileDebugUnitTestKotlin compileDebugAndroidTestKotlin` must pass. `AGENTS.md` and `.claude/agents/implementer.md` carry the same gate verbatim (this file doesn't load for Kotlin-only changes).
- **While iterating:** use targeted runs (`--tests "…SpecificClassTest"`, or `compileDebugKotlin` alone) with `-q`/`--console=plain` and grep for `FAILED`/`error:`/`Exception`. These supplement the full gate; they never replace it.
- **Network exception:** if the gate fails *only* because an external service (e.g. Maven Central) is down after one retry, report the command and error in the handoff/PR body. A failure caused by the change never qualifies.

## Diagnosing a failed CI check (#684)
- Read the check run's annotations first (`get_check_run`/`get_job_logs` annotations). They usually name the failing test and line.
- Use the raw log only when annotations don't localize the failure (e.g. a hung job). Save it to a file and grep it for `FAILED`, `Exception`, `AssertionError`, `waitUntil` or `Timed out`; never read it inline.

## Cloud / in-session builds (#419, #544, #548)
- **Setup:** enablement is environment config — allowlist `dl.google.com` and run `scripts/cloud-setup.sh` as setup. It uses `/opt/android-sdk` when writable (else the user SDK dir; `ANDROID_HOME`/`ANDROID_SDK_ROOT` override), installs the SDK, and seeds the wrapper dist from the pre-installed Gradle.
- **Platform package:** derived from `compileSdk`'s major (never hardcode it).
  - From API 37 there is **no bare `platforms;android-<major>`**, only `android-37.0`, `-37.1`, ….
  - The script prefers a bare id, else the lowest minor, and falls back to `--channel=3` (install included) only when stable has nothing.
  - If nothing resolves it warns and continues (AGP downloads missing components); don't restore a hard failure.
  - `CMDLINE_TOOLS_BUILD` must stay new enough to see the compileSdk platform (#544).
- **Gradle major must match the wrapper's** (AGP 9 needs Gradle 9); the script refuses to seed a different major. On a Gradle 8 image, allowlist `services.gradle.org`, `downloads.gradle.org` and `release-assets.githubusercontent.com` instead.
- CI remains the authoritative gate; instrumented tests need CI's emulator.
