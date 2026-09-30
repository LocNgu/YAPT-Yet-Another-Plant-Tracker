# Product ADR-0058: Settings is a root tab, admitted as a named exception to ADR-0019's tab rule

**Status**: accepted

**Date**: 2026-09-30

## Context

Product ADR-0019 Q2 admits a screen as a bottom-bar tab only if it is opened many times per session,
shows a live view of the whole collection, and has no in-progress state lost by tab-switching. Q3
applied that rule to Settings and kept it behind a gear icon on the Plants top bar.

Product ADR-0054 then made Care the start destination, and Care has no route to Settings at all: a
user on the first screen of the app has to switch to Plants to find the gear. Issue #852 also removes
Calendar, which would leave the bar with two items, below Material 3's guidance of three to five
destinations. The owner decided in #839 to put Settings in the bar (#855).

Settings does not honestly meet Q2's first criterion — it is not opened many times per session. The
options were to bend that criterion, to rewrite Q2 for everyone, or to admit Settings by name and leave
Q2 untouched for future tabs. The second criterion is met (it is a whole-app concern). The third is met
only if leaving Settings mid-backup/restore is prevented, since those operations run in a modal progress
dialog owned by the Settings screen and ViewModel.

## Decision

- **Settings is a named exception to ADR-0019 Q2.** Q2's three criteria are unchanged and still gate
  every future tab; Settings is admitted because the bar needs a third destination and Care needs a
  route to Settings, not because it satisfies "opened many times per session".
- **Settings is a real root destination.** It sits in the bar as **Care · Plants · Calendar · Settings**
  (until #852 removes Calendar, leaving Care · Plants · Settings; order per product ADR-0054), uses the
  same `popUpTo(start) { saveState }` / `launchSingleTop` / `restoreState` switching as the other tabs,
  keeps the bar visible, and has no back arrow. System Back from Settings pops to Care, like Plants.
  Graveyard stays a nested screen (bar hidden, Back returns to the Settings tab); What's New stays a sheet.
- **The Plants top-bar gear is removed.** The bar is the single entry point to Settings; this replaces
  ADR-0019 Q3.
- **Q2's third criterion is met by a bar guard.** While a backup or restore runs, every bar item is
  disabled (not hidden) and the existing back-press block stays, so the progress dialog cannot be
  abandoned. `SettingsScreen` reports the in-progress flag upward and resets it on dispose, so the bar
  is never stuck disabled if Settings leaves composition mid-operation. Moving backup/restore into an
  application-scoped job was rejected as a far larger change for no user-visible gain.
- **Restore success is unchanged:** reset to Care, then open Plants with the restored-counts message.

## Consequences

- Settings is reachable from every root tab in one tap, and the bar keeps three or more destinations
  after Calendar is removed.
- Dialogs, the time picker and the developer-mode tap counter reset on re-entry (they are `remember`ed),
  preserving #520's countdown-restart behaviour; Settings' scroll position survives tab switches.
- A navigation started from outside the bar (e.g. a notification tap) during a backup is not blocked;
  that was already possible before this change.
- This amends ADR-0019's Q2 (a named exception, criteria unchanged) and Q3 (placement). Q1's tab set,
  Q4's visibility rule (now including Settings among the visible roots) and the tab-switching semantics
  remain in force. Product ADR-0054's root set gains Settings.
