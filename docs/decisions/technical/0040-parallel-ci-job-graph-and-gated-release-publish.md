# Technical ADR-0040: CI runs the test jobs in parallel, drops `build`, and publishes the release from a separate gated `publish` job

**Status**: accepted

**Date**: 2026-10-25

## Context

Issue #941 measured the `Android CI/CD` pipeline at 23-36 minutes, and sub-task 1 (technical ADR-0039) warmed the Gradle
cache. The job graph was still serial: `changes` -> `test` -> `build` -> `instrumented` -> `release`.

- `build` ("Build & Test") only ran `assembleDebug` and uploaded `debug-apk-<sha>`, which nothing downloads.
  `instrumented` builds its own debug and androidTest APKs through `connectedDebugAndroidTest`, yet waited on `build`.
- `release` took 11m26s on run 1547: `testReleaseUnitTest` 5m19s, `lintRelease` 2m32s and `assembleRelease` 3m13s. About
  1m30s of the last step is AGP's `lintVitalAnalyzeRelease`/`lintVitalRelease` re-analysing the sources `lintRelease` had
  just checked. They run because `app/build.gradle.kts` has no `lint {}` block, so `checkReleaseBuilds` defaults to
  `true`. `release` also built and published in one job, so it held `contents: write` while it ran the build and the
  signing secrets.

Constraints from the owner on #941: every existing gate (Detekt, debug unit tests, debug lint, instrumented tests,
release unit tests, release lint, release assemble) must still run before merge and before release; the push to
`main` always re-runs every gate; no required status checks are configured, so job names may change, but job ids `test`
and `instrumented` stay because setup-gradle's cache keys include the job id.

Alternatives considered and rejected:

- **A tree-identity skip** (skip the `main` gates when the tree equals an already-green `develop` tree). Rejected by the
  owner: the push to `main` always re-runs every gate.
- **`lint { checkReleaseBuilds = false }`.** It would also disable the check for a local `assembleRelease`. Excluding the
  two tasks only in CI keeps the local check.
- **Keeping the release serial** (after `instrumented`). Safe, but it puts a ~11 minute job on the critical path for no
  gain, since it shares no outputs with the other jobs.
- **One combined release-build + publish job.** It would keep write permission and the signing secrets in the same job
  that runs Gradle.
- **A `workflow_dispatch` dry run** (release build without publish). It would not exercise the new and untestable parts
  (`publish`'s `needs`, the artifact download, `gh release create`, the write permission), would add a trigger that every
  `github.event_name == 'push'` clause would then have to account for, and would be a new way to run a job holding the
  signing secrets.

## Decision

- **Remove the `build` job.** `instrumented` has `needs: changes` only, so `test` and `instrumented` run in parallel.
  `instrumented` uploads `debug-apk-<sha>` (`if: always()`, `if-no-files-found: ignore`, 5-day retention on `main` and
  `develop` pushes, otherwise 1). A PR whose instrumented path filter is false therefore has no debug APK.
- **Split `release` in two on a push to `main`.**
  - `release-build` ("Build Release APK"): no `needs` on the test jobs, so it runs in parallel with them. It runs
    `testReleaseUnitTest`, `lintRelease` and `assembleRelease`, and uploads `release-apk-<sha>` and the release test
    results. It is the only job that holds the signing secrets and it has read-only permissions.
  - `publish` ("Publish GitHub Release"): `needs: [test, instrumented, release-build]`, `contents: write`. It checks out,
    reads `version.properties`, downloads `release-apk-<sha>` and runs the existing idempotent `gh release create`. It
    keeps GitHub's default `needs` semantics (no `always()`), so it is skipped unless every gate succeeded on that
    commit. It is the only job with write permission.
- **`android.yml` sets workflow-level `permissions: contents: read`**, so "only `publish` can write" does not depend on
  the repository's default token permissions. `changes` keeps its own block (it also needs `pull-requests: read`).
- **CI's `assembleRelease` passes `-x lintVitalRelease -x lintVitalAnalyzeRelease`**, with a comment: `lintRelease` ran
  the full lint on the same sources in the previous step. Both task names exist under AGP 9.4.1; excluding them removes
  every `*LintVital*` task from the `assembleRelease` graph.
- Both concurrency groups stay as they are: the workflow-level `develop`-only group and `instrumented`'s job-level group.

## Consequences

- A PR's critical path becomes `max(test, instrumented)`. A push to `main` becomes
  `max(test, instrumented, release-build)` plus `publish`.
- If any of the three gates fails on a `main` push, `publish` is skipped and no release is created. The other jobs are
  not cancelled, and that wasted runner time is accepted.
- A newer `main` push cancels the earlier `instrumented` run (job-level group), which skips the earlier commit's
  `publish`, as the old `release` did. Recover by re-running that commit's jobs.
- The `main` path (`release-build` and `publish`) cannot run in a PR. It is validated by actionlint, a read of every
  `if:`/`needs:`, and review; the first push to `main` after merge is the real test. If `publish` fails or is skipped
  after a green `release-build`, re-run `publish` (it skips when the tag exists) or create the release by hand from the
  `release-apk-<sha>` artifact (30-day retention).
- `actions/download-artifact` is pinned to `v8` to pair with `upload-artifact@v7`; Dependabot manages action versions.
- `release-build` has no Gradle-cache writer of its own, as noted in technical ADR-0039.
