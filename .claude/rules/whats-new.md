---
description: What's New content writing rule, file layout, length test and the collapsible sheet
paths:
  - "**/WhatsNew*.kt"
---

# What's New

Design: product ADR-0072 (short entries, collapsed sheet), built on product ADR-0032 (`unreleased` / `all` tiers).
`CHANGELOG.md` is the detailed technical record; What's New is a **short plain-language summary of it, not a mirror**.
A user-visible PR writes two texts: a detailed changelog bullet and a short What's New bullet.

## Writing rule

**Do**
- One sentence, one change. Aim for 100 characters or fewer; CI fails above 120.
- Write from the user's side: what they can do now, or what no longer goes wrong.
- Use the app's own words (Care, Plants, Settings, Home tab), at most once per entry.
- Merge related changes from one PR into one bullet; a large feature gets two or three. Detail goes in `CHANGELOG.md`.
- Added is a new capability, Fixed is something that was wrong, Changed is an existing thing that now behaves or
  looks different.

**Don't**
- Use internal vocabulary: base or effective interval, override, horizon, confidence, bootstrap, adaptive model,
  rounding, flag names, migration, DataStore.
- Cite issue, PR or ADR numbers.
- Describe tap-by-tap mechanics, defaults, edge cases or caveats.
- Write entries for internal-only work: CI, toolchain, refactors, tests, dev-mode plumbing. Those get a
  `CHANGELOG.md` entry only.
- Copy a `CHANGELOG.md` bullet and trim it.
- End with a full stop or use emoji (no existing entry does).

Good: `Away mode: plan a trip and YAPT pauses care reminders while you're gone`. Bad: the same feature with the
edit/cancel/I'm-back mechanics and the banner timing spelled out in one 300-character entry.

## Layout

- `WhatsNewContent.unreleased` takes this PR's bullets (never `all`); it is promoted into `all` at release-cut
  (`release.md`). Promoted entries are already short, so promotion is a copy, not a rewrite.
- `WhatsNewContent.all` is the released entries, newest first. `legacyReleaseNotes` (`WhatsNewLegacyReleases.kt`,
  0.20.0 and older) is appended after it. Source lines stay within 120 columns; split a string literal with `+`.
- Sections stay Added / Fixed / Changed. A release with no entries is not created.
- Released entries were rewritten once in #958 (a one-time exception recorded in product ADR-0072). They are history
  again: do not rewrite them without a new decision.

## Test

`WhatsNewContentTest` runs over `listOf(WhatsNewContent.unreleased) + WhatsNewContent.all` (and `all` already ends
with the legacy releases). It fails on: length over 120 (`MAX_ENTRY_LENGTH`), blank / untrimmed / multi-line, a second
sentence (`". "`), a denylist hit (`#\d+`, `ADR`, `base interval`, `horizon`, `override`, case-insensitive), a release
with no entries, duplicate `versionCode`s. The failure message names the version, section and text; rephrase the
entry, do not allow-list it. `topEntryMatchesCurrentVersionName` is unchanged. The `unreleased` sentinel passes every
check trivially.

## Sheet behaviour

- `WhatsNewSheet(onDismiss, releases = WhatsNewContent.all)`; releases sorted by `versionCode` descending.
- Only the highest `versionCode` starts expanded. Every header is a clickable `Role.Button` row with `heading()`, an
  Expanded / Collapsed `stateDescription` and an Expand / Collapse `onClickLabel`, mirroring `CareGroupHeader`
  (`TodayCareGrid.kt`). Several can be open at once.
- Expanded state is a `rememberSaveable` set of `versionCode`s inside the sheet, never written to DataStore: each
  open starts with only the newest expanded. A collapsed release composes none of its sections or bullets.
- Auto-shown and Settings sheets are the same composable; dismissing the auto-shown one stores
  `LAST_SEEN_VERSION_CODE` (unchanged, in `NavGraph`).
- UI test: `WhatsNewSheetTest` (androidTest) asserts state description and presence/absence, not tree structure.
