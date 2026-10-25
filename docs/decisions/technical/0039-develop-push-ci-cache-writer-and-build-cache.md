# Technical ADR-0039: Android CI runs on develop pushes to write the Gradle cache; the build cache is on and Test tasks are never cacheable

**Status**: accepted

**Date**: 2026-10-25

## Context

Issue #941 measured a full `Android CI/CD` run at 23-36 minutes, with a large share spent on cold Gradle starts and
recompiling the same code in every job. The cause is that the Gradle cache has never been populated:

- `gradle/actions/setup-gradle` restores its cache everywhere but writes it only from the default branch, which is
  `develop`. `android.yml` never ran on a `develop` push (`push: branches: [main]`, #260), so every job logged
  `Entries: 0 restored, 0 saved ... cache is read-only`, including the push-to-`main` run.
- Each job's first Gradle invocation sat silent for 54-85 s between "Welcome to Gradle" and "Configure project" (start-up
  plus dependency resolution into an empty `~/.gradle`), against 7-11 s for later invocations in the same job. Each job
  also recompiled the same debug code (`test`, `build`, `instrumented`) because there was no build cache to take it from.
- #260 kept `develop` pushes out of `android.yml` to avoid a double run while a `develop` -> `main` release PR was open.
  That reason is obsolete: release PRs now come from a static `release/X.Y.Z` branch that nothing else pushes to
  (`.claude/rules/release.md`).

Alternatives considered and rejected:

- **A separate warm-up workflow on `develop`.** `setup-gradle` keys its entries per workflow job
  (`gradle-home-v2|Linux-X64|test[<hash>]-<sha>`), so a different workflow would write entries `android.yml` never reads.
- **A nightly scheduled run.** Up to a day stale, and no per-merge check of the merged result.
- **`cache-read-only: false` on PR runs.** PR-scoped cache entries are not readable by other PRs, and they would churn the
  10 GB repository cache.
- **Caching test results.** Several unit tests depend on the calendar date or the clock (#921; #936 failed a release-prep
  run only because the date moved), so a pass restored from the build cache on another day could hide a real failure.

## Decision

- **`android.yml` runs on pushes to `develop`** as well as `main` and PRs. On a `develop` push the `changes`, `test`,
  `build` and `instrumented` jobs run; `release` stays `main`-only. `build` is still there only until the job-graph
  restructure in #941's second sub-task removes it. The run is the Gradle-cache writer for every job and a post-merge
  check of the merged result.
- **Only `develop` pushes share a concurrency group** (`android-develop`, `cancel-in-progress`). Every other run gets a
  unique group (`android-<run_id>`), so PR and `main` runs are never cancelled by the workflow-level group.
- **`org.gradle.caching=true`** in `gradle.properties`, so compile, KSP and dexing outputs come from the build cache
  that `setup-gradle` stores with the Gradle cache. `org.gradle.parallel` is not set (single module, no effect) and the
  configuration cache is not adopted here.
- **`Test` tasks are never cacheable.** `app/build.gradle.kts` sets `outputs.cacheIf { false }` on every `Test` task,
  covering `testDebugUnitTest` and `testReleaseUnitTest`.

## Consequences

- Each merge to `develop` costs extra runner time (free on this public repo), but nobody waits on it.
- A newer `develop` merge cancels the older run. That is safe because the newer tree contains the older one.
- The first PR after a `develop` run gets a warm cache. PR, release-PR and `main` runs read `develop`'s entries
  read-only, so they never add cache entries of their own.
- Each merge saves several large cache entries; the oldest are evicted under the 10 GB repository limit.
- A `develop` -> `main` PR (not the documented release flow) would double-run again, as #260 feared.
- Room's schema export is a KSP argument (`room.schemaLocation`), not a declared task output, so a KSP task restored from
  the build cache would not rewrite `app/schemas/`. That matches today's up-to-date behaviour, where an unchanged KSP task
  also skips the export; adopting the Room Gradle plugin would be the fix if it ever matters.
- A test result is never restored from the build cache, so a restored compile cannot hide a date- or clock-dependent
  failure; the tests still execute on every CI run.
