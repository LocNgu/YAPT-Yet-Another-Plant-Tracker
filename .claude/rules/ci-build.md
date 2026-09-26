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

## Toolchain (AGP 9.4.0 / Gradle 9.7.1 / Kotlin plugins 2.4.10 / KSP 2.3.11)
- Compose BOM 2026.09.00 · compileSdk 37 · targetSdk 35 · minSdk 26.
- **Kotlin and KSP version numbers need not match** — KSP publishes on its own independent release line; the
  old `<kotlin>-<ksp>` scheme (e.g. `2.0.21-1.0.28`) is legacy. A KSP `2.3.x` number is therefore *not* a claim
  about a Kotlin `2.3.x` compiler: Kotlin's own KSP quickstart pairs Kotlin 2.4.20 with KSP 2.3.x, and this repo
  builds green on Kotlin plugins 2.4.10 + KSP 2.3.11. So a grouped Dependabot PR whose Kotlin and KSP numbers
  disagree is **not** self-evidently a red-CI PR — there is no numeric alignment to enforce, and no point hunting
  for a KSP release whose number matches Kotlin's. Validate the pairing through CI instead. The stdlib coupling
  `.github/dependabot.yml` documents (KotlinX libs + MockK against the Kotlin stdlib) is a separate, real
  constraint and still holds.
- **AGP 9 provides Kotlin compilation itself** — the standalone `org.jetbrains.kotlin.android` plugin is NOT applied
  and AGP 9 errors if it is present. Do not re-add it. Compose/serialization plugins stay, pinned to 2.4.10;
  KSP to 2.3.11.
- `kotlinOptions { jvmTarget }` was migrated to top-level `kotlin { compilerOptions { jvmTarget.set(JVM_17) } }`.
- **`gradle-wrapper.properties` is the single source for the Gradle version** (#726). `setup-gradle` in
  `android.yml` uses `gradle-version: wrapper`, so all four jobs resolve it from the wrapper — bump the wrapper
  alone and CI follows. Never hardcode a version there: an explicit `gradle-version` overrides the wrapper, which
  is how CI silently sat on 9.6.1 while the wrapper moved to 9.7.1. Note Dependabot's gradle ecosystem does not
  update the wrapper, so that bump stays manual.
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

## Robolectric's application is `TestYaptApplication`, not `YaptApplication` (#757)
Every unit test runs against `com.yapt.planttracker.TestYaptApplication`: the production application
with `launchAppStartWork()` (the `onCreate` coroutine running `writeDefaultReminderTimeIfAbsent` +
`SeasonalGraduationFixup`) overridden to a no-op — the sole reason `YaptApplication` and that one
method are `open`.
**Two independent routes select it, and the class name is load-bearing.** Robolectric's
`AndroidTestEnvironment` resolves `Test` + the manifest application's simple name in the same package
*before* falling back to the manifest class, so `TestYaptApplication` applies by naming convention
alone — verified in robolectric-4.16.1 bytecode and by deleting the properties file and watching tests
still get it. `app/src/test/resources/robolectric.properties` is kept as the explicit, greppable
registration that survives renaming the class off that convention (and fails loudly if it names a class
that doesn't exist). Don't describe either one as "the" wiring: rename the class and you silently drop
the convention route, so keep the properties file in step.
Why: Robolectric builds a fresh application per *test method*, while `PlantDatabase.getInstance()` and
the `settingsDataStore` delegate are process-wide singletons shared by every test in a JVM fork. The
fire-and-forget `Dispatchers.IO` launch therefore ran against the same database as whatever test was
executing, and whenever the #702 fixup's plant snapshot landed after a test's fixture insert it
recomputed that fixture's `wateringBaseIntervalDays` (7.0 → 7.72 at the default 0.35 amplitude) — the
one column `SkipWateringReceiverTest`'s product-ADR-0007 invariant guard asserts is never written. The
window opened at most once per fork (the fixup marks itself done after its first non-empty pass),
which is why it never reproduced on a re-run or in class isolation.
Consequences to keep in mind:
- Don't reintroduce app-start work that tests silently race. New `onCreate` background work goes inside
  `launchAppStartWork()`, and gets its own direct test rather than relying on app start to run it.
- Don't add `@Config(application = …)` pointing back at `YaptApplication` — that opts a class back into
  the race. `TestYaptApplicationTest` guards the end state (which application Robolectric instantiates),
  not any one wiring mechanism: it catches a config pointed back at the production application, but
  passes if only the properties file goes missing, since the naming convention still holds there.
- A test that genuinely needs app-start behaviour should drive it explicitly (call the underlying
  function), never wait on the launch.

## Diagnosing a failed CI check (#684)
Don't pull the full raw job log as the first move — it's routinely 50K+ characters and most of it is
noise. Check the failing check run's conclusion/annotations first (the GitHub API's check-run details,
e.g. `get_check_run`/`get_job_logs` annotations) for the specific failing test name and line; annotations
usually localize a normal assertion failure or compile error in one or two lines, which is enough to go
fix it without ever touching the raw log.
Only fall back to the raw job log when annotations don't localize the failure — this happens for a
genuinely hung job with no clean per-test failure line, e.g. #679/#682's instrumented-test job: a bad
blanket MockK stub left a call path unmatched, the test hung on `waitUntil` until the whole job timed
out, and no annotation pointed at a specific assertion. In that case, save the log to a file rather than
reading it inline (it will blow past the tool's token cap), then `grep` the file for failure markers
(`FAILED`, `Exception`, `AssertionError`, `waitUntil`, `Timed out`) instead of reading the whole thing —
the grep hits are usually enough to locate the offending test class/stub without ever loading the bulk
of the log into context.

## Iterating fast without skipping the mandatory full suite (#684)
`./gradlew detekt lintDebug compileDebugKotlin compileDebugUnitTestKotlin compileDebugAndroidTestKotlin`
must succeed **before every push that opens or updates a PR** — round 2+ fix-round pushes are not an
exception. This matches `AGENTS.md`'s "Before opening or updating a pull request" gate verbatim (both
docs point agents at this shared file precisely so the two can't drift apart on this); nothing below
proposes a per-agent or per-round carve-out from it. This file is path-scoped and doesn't load for a
Kotlin-only change, so `.claude/agents/implementer.md` also carries this same gate verbatim in its own
"Before every push" section — that's the copy that actually loads on a plain Kotlin/Compose PR (#807).
The token/time savings on a fix round come from *how* you run checks while iterating, not from skipping
any of them before the push:
- While chasing one specific reviewer finding, first reproduce/confirm it with a targeted run
  (`./gradlew testDebugUnitTest --tests "com.example.SpecificClassTest"` or `compileDebugKotlin` alone
  for a compile-only fix) for a fast fail/pass signal — this is a debugging aid to iterate faster, not a
  substitute for the full mandatory suite above, which must still run once before the push.
- Pipe every run through `-q`/`--console=plain` and grep the output for `FAILED`/`error:`/`Exception`
  instead of reading full verbose console output — this is where the actual context savings come from.

**Narrow network exception (#807):** if the gate can't complete solely because an external dependency
service (e.g. Maven Central) is unavailable after one retry, report the failing command and the external
error in the handoff and PR body instead of pushing unverified silently; CI remains authoritative for
that run. A compile, test, lint, or Detekt failure caused by the change itself never qualifies for this
exception — only a genuine external-service outage does.

## Release build (#4)
`isMinifyEnabled = true`, `isShrinkResources = true` on the release build type. ProGuard rules keep WorkManager
workers and Room DAOs (both reached via reflection) from being stripped/renamed.

## Cloud / in-session builds (#419, #544, #548)
Enablement is environment config, not repo: allowlist `dl.google.com` and run `scripts/cloud-setup.sh` as setup.
It uses `/opt/android-sdk` when writable on Linux, otherwise the platform's user SDK directory; an explicit
`ANDROID_HOME` or `ANDROID_SDK_ROOT` overrides that default. It installs the SDK and seeds the wrapper dist from
the pre-installed Gradle in Linux cloud environments; macOS local environments use the wrapper normally.
The script derives the `compileSdk` *major* from `app/build.gradle.kts` and resolves the real platform package id
from it — don't hardcode a platform in it.
`CMDLINE_TOOLS_BUILD` bootstraps the managed SDK and is the safe fallback while an older user-owned
`cmdline-tools;latest` is preserved in place. The bootstrap must remain current enough to see the project's
compileSdk platform (a 2023 pin couldn't see API 37 — #544).
**The platform package id isn't always the bare major.** Starting at API 37, Google stopped publishing a bare
`platforms;android-<major>` package — only major.minor ids exist (`android-37.0`, `android-37.1`, ...); older
majors (35, 36) still ship the bare id alongside minors. `platforms;android-37` fails identically on *every*
channel because it never existed anywhere — this was first misdiagnosed as a stable-channel gating problem (#548),
disproved by reproducing the identical "Failed to find package" on stable and `--channel=3` alike, and by
installing `platforms;android-37.0` cleanly from stable. The script queries `sdkmanager --list`, prefers the bare
id when one exists, and otherwise picks the lowest minor (`<major>.0` is the configuration verified to satisfy
AGP's integer `compileSdk`); it only falls back to `--channel=3` when nothing matches in stable, for a genuinely
preview-only major — and the install itself also runs with that same `--channel=3` flag, since a package that only
resolved via canary won't install under sdkmanager's stable default either. If no channel resolves it, the script
warns and continues rather than aborting: AGP downloads missing SDK components itself once licenses are accepted,
so the verification build is the real test. Don't restore a hard failure there.
**Only works if the pre-installed Gradle matches the wrapper's major** (AGP 9 needs Gradle 9): the script refuses to
seed a different major rather than trading a wrapper-download failure for a "minimum supported Gradle version" one. On a
Gradle 8.x image, allowlist the wrapper's hosts (`services.gradle.org`, `downloads.gradle.org`,
`release-assets.githubusercontent.com`) so the real dist downloads instead. CI is unaffected (`setup-gradle` downloads
its own pinned Gradle) and remains the authoritative gate. Instrumented tests still need CI's emulator.
