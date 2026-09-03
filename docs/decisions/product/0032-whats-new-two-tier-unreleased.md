# Product ADR-0032: `WhatsNewContent.kt` gets a two-tier `unreleased`/`all` shape, mirroring `CHANGELOG.md`

**Status**: accepted
**Date**: 2026-09-03

## Context

Product ADR-0010 stated that "implementers must keep `WhatsNewContent.all` up to date every PR." In
practice, `WhatsNewContentTest.topEntryMatchesCurrentVersionName` asserts:

```kotlin
assertEquals(BuildConfig.VERSION_NAME, WhatsNewContent.all.first().versionName)
```

`all.first().versionName` must therefore always equal the *currently released* `BuildConfig.VERSION_NAME`.
Prepending an entry for unreleased work during a feature PR makes `all.first().versionName` run ahead of
`BuildConfig.VERSION_NAME`, and the test fails — so following ADR-0010's documented workflow always turns
CI red. In practice `WhatsNewContent.kt` was only ever touched at release-cut (every historical commit that
touched it is a version-bump commit), contradicting the documented per-PR workflow (issue #601).

The original issue proposed the opposite fix — moving the update to release-cut and dropping the per-PR
requirement — but `CHANGELOG.md` already solves the identical problem (an always-current per-PR log vs. a
test/reader that wants only released history) with a two-tier `[Unreleased]` / versioned-heading shape. The
real bug is the test enforcing the wrong invariant on a single-tier list, not the workflow.

## Decision

`WhatsNewContent` gains a second property, `unreleased: ReleaseNotes`, non-null, with a sentinel value of
`ReleaseNotes(versionCode = 0, versionName = "Unreleased")` (`added`/`fixed`/`changed` left at their
`emptyList()` defaults). Implementers append bullets to `unreleased` on every PR with a user-visible change
(CLAUDE.md workflow step 5) — never to `all`.

`WhatsNewContent.all` keeps ADR-0010's original invariant unchanged: released entries only, newest first.
`WhatsNewSheet` is untouched — it still reads only `all`, so unreleased content is never shown to a user.

At release-cut (`.claude/rules/release.md`), `unreleased` is promoted: its `versionCode`/`versionName` are
set to the real release values, the resulting entry is prepended to `all`, and `unreleased` is reset back to
its sentinel — mirroring `CHANGELOG.md`'s existing `[Unreleased]` → versioned-heading promotion exactly. If
`unreleased` is still at its empty sentinel at release-cut (a chore-only release cycle with nothing
user-visible), it is not prepended to `all` — an entry with a version heading and no bullets would be a
visual artifact in the sheet — and the reset step is then a no-op.

`WhatsNewContentTest.topEntryMatchesCurrentVersionName` stays byte-for-byte unchanged; it keeps passing
because `all` never receives a mid-cycle entry. A new test,
`WhatsNewContentTest.unreleasedIsClearedAfterRelease`, asserts `WhatsNewContent.unreleased ==
ReleaseNotes(versionCode = 0, versionName = "Unreleased")` via data-class equality, so it fails if a future
release-cut PR forgets to reset `unreleased`, or forgets a bullet still sitting in it after promotion.

## Consequences

- Supersedes ADR-0010's stated mechanism ("implementers must keep `WhatsNewContent.all` up to date every
  PR"). The accurate rule going forward: keep `unreleased` current every PR; `all` only changes at
  release-cut.
- `topEntryMatchesCurrentVersionName` keeps passing unmodified, and is now genuinely always true rather than
  incidentally true only right after a release.
- A forgotten reset or promotion at release-cut now fails CI (`unreleasedIsClearedAfterRelease`) instead of
  silently shipping stale or duplicated notes.
- `.claude/CLAUDE.md`, `.claude/rules/release.md`, and `.github/pull_request_template.md` wording updated to
  say "append to `unreleased`" / "promote `unreleased` into `all`" instead of the previous single-tier
  phrasing.
