# Technical ADR-0039: Android CI runs on develop pushes to write the Gradle cache; the build cache is on and Test tasks are never cacheable

**Status**: accepted — job-set clause (the `build` job) amended by [ADR-0040](0040-parallel-ci-job-graph-and-gated-release-publish.md)

**Date**: 2026-10-25

## Context

Issue #941 measured a full `Android CI/CD` run at 23-36 minutes, with a large share spent on cold Gradle starts and
recompiling the same code in every job. The cause is that the Gradle cache has never been populated:

- `gradle/actions/setup-gradle` restores its cache everywhere but writes it only from the default branch, which is
  `develop`. `android.yml` never ran on a `develop` push (`push: branches: [main]`, #260), so the `test` job's summary
  logged `Entries: 0 restored, 0 saved ... cache is read-only` in both the push-to-`main` run (1547) and a PR run (1548).
  Only the `test` job's summary was inspected; the other jobs use the same action and defaults.
- Each job's first Gradle invocation sat silent for 54-85 s between "Welcome to Gradle" and "Configure project" (start-up
  plus dependency resolution into an empty `~/.gradle`), against 7-11 s for later invocations in the same job. Each job
  also recompiled the same debug code (`test`, `build`, `instrumented`) because there was no build cache to take it from.
- #260 kept `develop` pushes out of `android.yml` to avoid a double run while a `develop` -> `main` release PR was open.
  That reason is obsolete: release PRs now come from a static `release/X.Y.Z` branch that nothing else pushes to
  (`.claude/rules/release.md`).

Alternatives considered and rejected:

- **A separate warm-up workflow on `develop`.** `setup-gradle` tries an exact key first
  (`gradle-home-v2|Linux-X64|test[<hash>]-<sha>`), then falls back to the most recent entry for the same OS and job id,
  then the same OS. Another workflow's entries would therefore be found, but only through those weaker fallback keys. It
  would also cost a second full build per merge just to warm the cache, and it gives no post-merge check of the merged
  tree.
- **A nightly scheduled run.** Up to a day stale, and no per-merge check of the merged result.
- **`cache-read-only: false` on PR runs.** PR-scoped cache entries are not readable by other PRs, and they would churn the
  10 GB repository cache.
- **Caching test results.** This project has date- and clock-dependent tests (#921: a millisecond clock race in a unit
  test; #936: date-dependent rounding in an instrumented `CalendarScreenTest`, which failed a release-prep run only
  because the date moved), so a cached test pass from another day could hide a real failure.

## Decision

- **`android.yml` runs on pushes to `develop`** as well as `main` and PRs. On a `develop` push the `changes`, `test`,
  `build` and `instrumented` jobs run; `release` stays `main`-only. `build` is still there only until the job-graph
  restructure in #941's second sub-task removes it. The run is the Gradle-cache writer for each job that runs there
  (`test`, `build`, `instrumented`) and a post-merge check of the merged result. `release` never runs on `develop` (see
  Consequences).
- **Only `develop` pushes share a concurrency group** (`android-develop`, `cancel-in-progress`). Every other run gets a
  unique group (`android-<run_id>`), so PR and `main` runs are never cancelled by the workflow-level group.
- **`org.gradle.caching=true`** in `gradle.properties`, so cacheable tasks can be restored from the build cache that
  `setup-gradle` stores with the Gradle cache. Reuse across commits is narrower than within one commit: `versionCode` is
  `git rev-list --count HEAD` (`app/build.gradle.kts`) and is written into the generated `BuildConfig.VERSION_CODE`,
  which app code reads. The app module's `kspDebugKotlin`, `compileDebugKotlin` and test compile tasks should therefore
  miss the build cache from one commit to the next. What still hits across commits is the Gradle user home (plugins and
  dependencies, which is the 54-85 s cold start), transforms, external-library dexing and desugaring (`mergeExtDex*` took
  53-75 s per job in run 1547) and dependency resources. `org.gradle.parallel` is not set (single module, no effect) and
  the configuration cache is not adopted here.
- **`Test` tasks are never cacheable.** `app/build.gradle.kts` sets `outputs.cacheIf { false }` on every `Test` task,
  covering `testDebugUnitTest` and `testReleaseUnitTest`. AGP's `connectedDebugAndroidTest` is already non-cacheable
  (`Caching has been disabled for the task`).

## Consequences

- Each merge to `develop` costs extra runner time (free on this public repo), but nobody waits on it.
- A newer `develop` merge cancels the older run. That is safe because the newer tree contains the older one.
- The first PR after a `develop` run gets a warm cache. PR, release-PR and `main` runs read `develop`'s entries
  read-only, so they never add cache entries of their own.
- `release` never runs on `develop`, so it has no writer of its own. It restores only through the same-OS fallback key
  (another job's entry), and release-variant tasks (R8, release compile) are never in `develop`'s build cache. Read the
  release job's timings in #941's second sub-task with that in mind.
- Decoupling the debug `versionCode` from the commit count would let the app module's compile tasks hit the build cache
  across commits. That is a possible follow-up and an owner decision, not part of this change. The first post-merge PR
  run will show which tasks are `FROM-CACHE`.
- Each merge saves several large cache entries; the oldest are evicted under the 10 GB repository limit.
- A `develop` -> `main` PR (not the documented release flow) would double-run again, as #260 feared.
- Room's schema export is a KSP argument (`room.schemaLocation`), not a declared task output, so a KSP task restored from
  the build cache would not rewrite `app/schemas/`. That matches today's up-to-date behaviour, where an unchanged KSP task
  also skips the export; adopting the Room Gradle plugin would be the fix if it ever matters.
- A test result is never restored from the build cache, so a restored compile cannot hide a date- or clock-dependent
  failure; the tests still execute on every CI run.
