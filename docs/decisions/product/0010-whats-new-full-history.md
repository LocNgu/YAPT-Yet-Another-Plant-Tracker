# Product ADR-0010: What's New sheet shows full release history, not just the current release

**Status**: superseded by [ADR-0032](0032-whats-new-two-tier-unreleased.md)
**Date**: 2026-06-02

## Context

The original What's New sheet (ADR not written at the time) showed only the entries for the current release — the items that were new since the user's last-seen version. The intent was to avoid showing stale content to long-time users.

In practice this created two problems:

1. **First-time users saw nothing.** A fresh install sets `LAST_SEEN_VERSION_CODE = 0`, so all items are "new", but the current-release view had no fallback for showing older history.
2. **Users who want to revisit past release notes couldn't.** The Settings row to reopen the sheet showed only the current release's notes, not the full history.

Alternatives considered:

- **Filter to new-since-last-seen entries only**: what was already implemented. Compact but incomplete.
- **Show full history, sorted newest first**: every user gets the same view; users can scroll to see past releases; the auto-show trigger drives discovery.
- **Show full history but highlight new entries**: more informative, but adds UI complexity (different visual treatment per entry) with questionable payoff.

## Decision

`WhatsNewContent.all: List<ReleaseNotes>` holds the full release history. At render time, `WhatsNewSheet` sorts by `versionCode` descending to guarantee newest-first order regardless of declaration order. The `LazyColumn` is scrollable with a `weight(1f)` modifier; "Got it" button is pinned below.

The auto-show trigger fires when `BuildConfig.VERSION_CODE > LAST_SEEN_VERSION_CODE` (DataStore). Dismissing the auto-shown sheet updates `LAST_SEEN_VERSION_CODE` to `BuildConfig.VERSION_CODE`. Opening the sheet from Settings → "What's New" does not update the stored value, so the sheet re-shows on the next update as expected.

`ReleaseNotes` gains a `versionCode: Int` field so the sort is stable. Implementers must set this to a value ≤ the actual build `versionCode` for that release (the git commit count at build time).

## Consequences

- All users see the same sheet content; no special-casing for new vs. returning users.
- Users can scroll back through past releases at any time from Settings.
- Implementers must keep `WhatsNewContent.all` up to date every PR and set a reasonable `versionCode` for new entries.
- The sheet will grow over time as more releases are added; the scrollable layout handles this without UI changes.
