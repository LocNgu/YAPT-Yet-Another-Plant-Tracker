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
4. **Create the prep PR** — `claude/<branch>` → `develop`, title `chore: release prep for X.Y.Z` (docs/version-only).
   `subscribe_pr_activity` and drive it to green as usual.
5. **Wait for the human to merge the prep PR** before touching the release PR — do not open it yet, even as a draft.
6. **Once the prep PR is merged, cut the release branch** from the now-updated `develop`:
   `git fetch origin develop && git checkout -b release/X.Y.Z origin/develop && git push -u origin release/X.Y.Z`.
7. **Create the release PR** — `release/X.Y.Z` → `main`, title `Release X.Y.Z`. Body lists all Added/Fixed/Changed
   from the new CHANGELOG section.
8. **Human merges the release PR.** CI builds the signed release APK on merge to `main`. Delete `release/X.Y.Z`
   afterward — it was cut from `develop` *after* the prep PR already landed there, so `develop` already has
   everything on it; no back-merge needed.

**Why a release branch instead of `develop` → `main` directly:** a PR's head branch re-triggers CI on every push
to it. If the release PR tracked `develop` itself, merging the prep PR (or *any* other PR) into `develop` while
the release PR sits open re-runs its CI for no reason — this happened cutting 0.27.1, where merging the prep PR
immediately re-triggered the release PR's build. Cutting a dedicated `release/X.Y.Z` branch *after* the prep PR
merges gives the release PR a static head that nothing else pushes to, so its CI runs exactly once. This is also
why the release PR isn't opened until the prep PR has actually merged — opening it earlier against `develop`
would just reintroduce the same re-run on merge.

(Referring to these as "the prep PR"/"the release PR" rather than "PR #1"/"PR #2" is deliberate — GitHub
auto-links a bare `#<number>` to whatever issue or PR actually has that number in this repo, which is almost
never the release PR being described.)

No DB migration or new tests needed for a docs-only release-prep PR. Opening the prep PR is pre-authorized; so is
cutting the release branch and opening the release PR once the prep PR has merged. Merging either PR is
human-only.
