# Product ADR-0072: What's New uses short plain-language entries and a sheet that folds older releases away

**Status**: accepted

**Date**: 2026-10-26

## Context

The What's New sheet renders every release since the first one, fully expanded, in one scrolling list (product
ADR-0010, superseded by product ADR-0032, which kept that behaviour and added the `unreleased` tier). On `develop`
that was 49 releases and roughly 6,000 words. Nobody reads it, and the per-entry detail is the main reason:

- `WhatsNewContent.kt` held 103 entries averaging about 42 words each, the longest 92 words. Half of them ran over
  40 words.
- They read like `CHANGELOG.md` because product ADR-0032 said `unreleased` mirrors `CHANGELOG.md`'s `[Unreleased]`,
  and in practice entries were written at changelog level of detail: internal terms (base interval, override,
  horizon), tap-by-tap mechanics and caveats that mean nothing to someone just opening the app after an update.

Alternatives considered:

- **Only the releases since the user last opened the sheet, plus a "Show all" button.** Rejected. It needs a second
  persisted number besides `LAST_SEEN_VERSION_CODE`, a new button and a second code path, and the Settings entry
  point would still have to decide which view to show. It also hides history that a user who skipped several
  updates may want. A folded header row costs one tap and needs no new state.
- **Capping the sheet at the last N releases and dropping the rest.** Rejected. It deletes history the app already
  ships and adds an arbitrary N that someone has to revisit.
- **A writing guideline with no test.** Rejected. Entries were already supposed to be user-facing and drifted to
  changelog length anyway; a hard cap in CI is the only thing that stops creep, and a hard cap is cheap to satisfy
  once the entries are short.
- **Rewriting only the last few releases, or only new entries from now on.** Rejected. The sheet would stay long
  and uneven: the old releases would still read like a changelog under their collapsed headers.
- **Keeping the entries as they are and only collapsing.** Rejected. The entries are the problem as much as the
  length of the list; a user who opens one release still faces paragraphs.

## Decision

**Short entries with a hard cap.** Every What's New entry is one sentence, written from the user's side (what they
can do now, or what no longer goes wrong), aiming for about 100 characters. A JVM test in `WhatsNewContentTest`
fails any entry over 120 characters, any blank, untrimmed or multi-line entry, any entry with a second sentence
(`". "`), and any entry that matches a denylist of internal vocabulary (an issue or PR number, `ADR`, `base
interval`, `horizon`, `override`). The test covers `unreleased` as well as `all`, because a length cap is valid
mid-cycle (unlike asserting `unreleased` is empty, which product ADR-0032 rejects). The full writing rule lives in
`.claude/rules/whats-new.md`.

**Decoupled from `CHANGELOG.md`.** `CHANGELOG.md` stays the detailed technical record, one bullet per change with
issue numbers and mechanics. What's New is a short plain-language summary of it, not a mirror: a PR now writes two
texts, a detailed changelog bullet and a short What's New bullet. A change that is purely internal (CI, toolchain,
refactor, tests, dev-mode plumbing) gets a changelog entry and no What's New entry.

**A sheet that folds older releases away.** `WhatsNewSheet` opens with only the newest release (highest
`versionCode`) expanded. Every other release shows just its version header, a one-tap button that expands it.
Several releases can be open at once; any header, the newest included, can be collapsed again. The expanded set is
per sheet-open: a `rememberSaveable` set of `versionCode`s inside the sheet, never written to DataStore, so each
open starts with only the newest expanded. A collapsed release composes none of its sections or bullets. The
auto-shown sheet and the Settings sheet are the same composable with the same default. The header mirrors the Care
section header's semantics: one clickable button row with `heading()`, an Expanded or Collapsed state description and
an Expand or Collapse click label.

**One-time rewrite of released entries.** All entries in `WhatsNewContent.all` and `legacyReleaseNotes` were rewritten
once, by hand, to this style. Product ADR-0061's and product ADR-0062's clause that released What's New entries "are
history and are not rewritten" is a deliberate exception here, confirmed by the human on #958. The rewrite keeps what
each release shipped: a release that introduced or retired a feature still says so (0.35.0 still says misting and
Note can no longer be logged). Closely related bullets in one release and section were merged, purely internal items
were dropped, and a release left with nothing user-visible was removed (0.20.1). Retained releases keep their
`versionCode` and `versionName`. The exception is for this rewrite only: after it, released entries are history
again. Product ADR-0061 and product ADR-0062 carry a Status-line amendment pointing here, and `CHANGELOG.md`'s
history is not rewritten.

**What stays.** The auto-show trigger (`LAST_SEEN_VERSION_CODE`), the `unreleased` to `all` promotion at release-cut,
the `unreleased` sentinel, `WhatsNewContentTest.topEntryMatchesCurrentVersionName` (byte-for-byte unchanged), and the
full history in `all` plus `legacyReleaseNotes`. `ReleaseNotes`, `NavGraph`, the Settings row and the dev-mode reset
action are unchanged. There is no DB, backup or schema change.

**Amends.** Product ADR-0032's "mirroring `CHANGELOG.md`" wording (the content now follows the writing rule above
instead) and its "`WhatsNewSheet` is untouched" clause (the sheet now folds older releases). Product ADR-0010 stays
superseded.

## Consequences

- The sheet opens short: one expanded release plus about 48 collapsed header rows. A user who wants an old release
  taps its header; there is no "since you last opened" filter, no "Show all" button and no cap on the number of
  releases.
- Each PR with a user-visible change now writes two texts, a detailed `CHANGELOG.md` bullet and a short What's New
  bullet. The cost is one extra sentence; the benefit is that the sheet stays readable.
- A long or jargon-heavy entry now fails CI instead of creeping back. The cap and the denylist are blunt: a
  legitimately useful phrase that trips the denylist has to be rephrased rather than allow-listed.
- The older releases' wording is now less specific than their `CHANGELOG.md` sections. That is intended; the changelog
  is the record, and `CHANGELOG.md` was not touched.
- Product ADR-0061 and product ADR-0062 no longer say released What's New entries are never rewritten; they carry a
  Status-line note and this ADR is the single place the exception is recorded. Do not rewrite released entries again
  without a new decision.
- Every release in `all` must keep at least one entry, so release-cut still skips promoting an empty `unreleased`.
