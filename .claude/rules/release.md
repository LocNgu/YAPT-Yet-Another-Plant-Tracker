---
description: Release-cutting workflow (version bump, changelog promotion, two release PRs)
paths:
  - "version.properties"
  - "CHANGELOG.md"
  - "**/WhatsNewContent.kt"
---

# Release Workflow

Triggered when the human asks to cut a release ("do a release", "bump to X.Y.Z", "prepare a release PR").

1. **Determine the version** — ask if unspecified; semver (new features → MINOR, fixes only → PATCH).
2. **Update five files** on a `claude/<kebab>` branch:
   - `version.properties` — bump `MINOR`/`PATCH` (or `MAJOR`)
   - `CHANGELOG.md` — promote `## [Unreleased]` → `## [X.Y.Z] - <today>`, add a fresh empty `## [Unreleased]` above it
   - `WhatsNewContent.kt` — promote `unreleased` into `all`: set `unreleased`'s `versionCode`/`versionName` to
     the real release values, prepend the resulting entry to `all`, then reset `unreleased` back to
     `ReleaseNotes(versionCode = 0, versionName = "Unreleased")`. If `unreleased` is still at that empty
     sentinel (a chore-only release cycle with nothing user-visible), skip prepending it to `all` entirely —
     don't create an empty-bulleted entry — the reset is then a no-op
     (**`WhatsNewContentTest` fails at CI if the version bump is skipped** — `topEntryMatchesCurrentVersionName`
     asserts `all.first().versionName == BuildConfig.VERSION_NAME`; correctly clearing `unreleased` back to
     its sentinel at release-cut is a manual step enforced by this checklist, not by CI — same posture as
     `CHANGELOG.md`'s `[Unreleased]` promotion)
   - `README.md` — add any new features not already under Features
   - `.claude/CLAUDE.md` — add any missing conventions/pointers (no longer a big "completed" log)
3. **Commit + push** to the feature branch: `chore: bump version to X.Y.Z, promote changelog, update docs`.
4. **Create PR #1** — `claude/<branch>` → `develop`, title `chore: release prep for X.Y.Z` (docs/version-only).
5. **Create PR #2** — `develop` → `main`, title `Release X.Y.Z`. Body lists all Added/Fixed/Changed from the new
   CHANGELOG section; note PR #1 must merge first.
6. **Human merges both** (in order). CI builds the signed release APK on merge to `main`.

No DB migration or new tests needed for a docs-only release-prep PR. Opening both release PRs is pre-authorized;
merging is human-only.
