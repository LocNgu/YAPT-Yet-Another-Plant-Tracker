# Technical ADR-0041: CI runs the instrumented tests in two shards and builds their APKs while the emulator boots

**Status**: accepted

**Date**: 2026-10-25

## Context

After technical ADR-0040, `Instrumented Tests` runs in parallel with `Unit Tests & Lint` and is the critical path of
every PR run that touches app code (#952, sub-task 3 of #941). Warm `develop` run 1559 (Gradle cache restored, first run
after ADR-0040) took 9m33s for that job and 6m26s for `Unit Tests & Lint`:

| Phase of `Instrumented Tests` | Time |
|---|---|
| Job setup (checkout, JDK, setup-gradle) | 14 s |
| Emulator setup: SDK installs 31 s (system image 15 s), AVD 1 s, boot 33 s, animations off 4 s | 69 s |
| `gradle connectedDebugAndroidTest` before the first test (Gradle start 25 s, then KSP, compile, dex, package) | 2m16s |
| Test phase (`> Task :app:connectedDebugAndroidTest` → `BUILD SUCCESSFUL`) | 5m34s |
| Uploads and post steps | 18 s |

The test phase is almost all test bodies: 483 tests whose reported durations add up to 327 s. All of them run in one
`AndroidJUnitRunner` process with no orchestrator, so tests share process state in run order. The emulator setup and the
APK build ran one after the other, although neither needs the other.

`Unit Tests & Lint` started Gradle three times (`detekt`, `testDebugUnitTest`, `lintDebug`). Each warm start took about
10 s before the first task, and the `detekt` call took 24 s although its only task came from the cache.

Alternatives considered:

- **Overlap only, no sharding.** Saves at most the 69 s of emulator setup, leaving the job at about 8.5 min.
- **Three shards.** About 110 s of tests per shard, but `Unit Tests & Lint` (about 6 min) then caps the critical path,
  so the third emulator buys little over two.
- **AVD snapshot caching.** Saves under a minute of boot; #941 already dropped it.
- **Android Test Orchestrator** (one process per test). It would isolate tests but adds a process start per test, which
  makes the test phase slower, not faster.

## Decision

- **`instrumented` is a two-entry matrix (`shard: [0, 1]`, `fail-fast: false`).** Each entry boots its own emulator and
  runs `connectedDebugAndroidTest` with `-Pandroid.testInstrumentationRunnerArguments.numShards=<job-total>` and
  `shardIndex=<shard>`. `AndroidJUnitRunner` assigns each test method to a shard by the hash of its JUnit description.
  Replaying that hash over run 1559's test names gives 238 tests and 162 s for shard 0 and 245 tests and 165 s for shard 1.
  The job id stays `instrumented` (setup-gradle's cache keys include it); the job is named
  `Instrumented Tests (shard N)`.
- **Per-shard names.** The job-level concurrency group is `instrumented-<PR number or ref>-shard-<N>`, and the results
  artifact is `instrumented-test-results-<sha>-shard-<N>`. Every shard builds the same debug APK, so only shard 0
  uploads `debug-apk-<sha>`.
- **The APKs build in the background while the emulator sets up and boots.** A step before the emulator runner starts
  `gradle assembleDebug assembleDebugAndroidTest` through `scripts/ci-background-build.sh start` and returns at once. The
  runner's script runs `ci-background-build.sh wait` first: it blocks until the build finishes, prints its log, and exits
  with its status, so a failed background build fails the job. `connectedDebugAndroidTest` then finds both APKs up to
  date. Both calls use the Gradle daemon the background build starts, so the test call does not pay a second cold start.
- **`Unit Tests & Lint` runs one `gradle detekt testDebugUnitTest lintDebug --continue`.** `--continue` still runs every
  check after one fails, and Gradle's failure summary names each failed task.
- **The `instrumented` path filter includes `.github/workflows/android.yml` and `scripts/ci-background-build.sh`**, so a
  PR that changes this job runs it before merge. Before this, a workflow-only PR skipped the emulator job, and ADR-0040's
  change to it was first exercised on `develop`.
- `publish`'s `needs: [test, instrumented, release-build]` waits for both shards, unchanged.

## Consequences

- Estimated warm PR wall-clock: about 6 min instead of 9.5 min. `Unit Tests & Lint` becomes the critical path again.
  The first PR run (1560) took 6m49s. Its shards got exactly the predicted 238 and 245 tests, the test call reused the
  background build's daemon, and every package task was up to date.
- setup-gradle's cache key includes a hash of the matrix values, so each shard has its own Gradle-cache entry, written
  by `develop` runs. Until a shard has one, it restores another `instrumented` entry instead.
- Instrumented runner-minutes roughly double (each shard pays its own setup and APK build). They are free because the
  repository is public.
- A test that passed only because an earlier test left state behind can fail once the shards split them. Such a failure
  is a real test-isolation bug: fix the test, never pin it to a shard or skip it.
- Shard balance comes from the hash of the test names, so adding or renaming tests moves tests between shards. A new
  heavy test class can unbalance them; the per-shard test-phase times in the job logs show it.
- The emulator boot and the APK build now compete for the runner's four vCPUs, so each is slower than alone. The gain is
  the overlap minus that contention.
- The job name changed from `Instrumented Tests` to `Instrumented Tests (shard N)`. ADR-0040 notes that no required
  status checks are configured; if one is added later, it must name both shards.
- A PR that changes only `android.yml` or the helper script now runs the emulator jobs.
