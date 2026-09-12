# Product ADR-0037: "Log watering" date entry moves into a bottom sheet

**Status**: accepted

**Date**: 2026-09-11

## Context

ADR-0034 introduced `LogWateringDatePickerDialog`, a plain Material3 `DatePickerDialog` that pops up
centered on screen every time a quick-water/quick-liquid-fertilize action is tapped. Every other
action-flow prompt on Plant Detail is a bottom sheet instead: `WateringReasonBottomSheet` ("Why was
it late?"/"Why now?"), `RescheduleReasonBottomSheet` ("Why put it off?"), and `WateringExplanationSheet`
("Why this date?"). Raised as UI feedback while reviewing ADR-0034's PR (#671) and explicitly deferred
to its own issue (#675) since it implies a presentation change, not the backdating feature itself.

Material3 ships no official bottom-sheet date picker, which the issue initially assumed would mean
building a custom calendar UI (day grid, month navigation, selection state) inside a `ModalBottomSheet`.
That assumption was wrong: `androidx.compose.material3.DatePicker` is already a standalone composable,
independent of `DatePickerDialog`. Only the surrounding container needed to change.

### Considered and rejected

**Build a custom bottom-sheet calendar.** Rejected once the above was confirmed — it would duplicate
day-grid, month-navigation, and selection-state logic Material3's `DatePicker` already provides for
free, for a purely cosmetic container swap.

**Extend the redesign to `AddCareLogScreen`'s picker and `RescheduleWateringDialog`'s custom-date
option.** Rejected as out of scope for this issue. `AddCareLogScreen`'s picker is the deliberately
different full-manual-entry flow (ADR-0034's Decision section already treats it as a separate surface);
`RescheduleWateringDialog`'s custom date has no reported inconsistency complaint. Scoping to
`LogWateringDatePickerDialog` alone keeps this a presentation-only, single-file change.

## Decision

**`LogWateringDatePickerDialog` presents its `DatePicker` inside a `ModalBottomSheet`, not a centered
`DatePickerDialog`.** The stock `DatePicker` composable, `TodayOrEarlierSelectableDates`, and the
existing date-math helpers (`localTodayAsUtcMidnightMillis()`, `utcMidnightMsToLoggedAtMillis()`) are
reused verbatim — nothing about selection, range, or the picked-date-to-`loggedAt` conversion changes,
only the container.

`rememberModalBottomSheetState(skipPartiallyExpanded = true)` is required, not incidental: the
default (`false`) lets a sheet this tall — a full calendar grid plus a button row — open only
partially expanded on smaller devices. On its own, though, `skipPartiallyExpanded` only removes that
partial-expansion anchor — it does not shrink oversized content to fit, so a viewport shorter than the
full `DatePicker` (landscape, a resized multi-window) can still clip the OK/Cancel row below the visible
sheet with no scroll affordance to reach it (caught by external review after this ADR's initial draft).
What actually guarantees the buttons stay reachable is pinning them outside the scrollable area: the
`DatePicker` sits in its own inner `Column` (`Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())`),
and the OK/Cancel `TextButton` row renders after it, in the outer sheet `Column`, never inside the
scrolling region. `weight(1f, fill = false)` caps the inner column at whatever space remains once the
always-visible button row is measured, so the calendar scrolls internally when it doesn't fit, without
forcing the sheet to full height when it comfortably fits on a normal portrait phone. Because
`ModalBottomSheet` has no confirm/dismiss button slots of its own (unlike `DatePickerDialog`), the sheet
supplies its own OK/Cancel `TextButton` row, reusing the existing `R.string.ok`/`R.string.cancel`
strings so the instrumented test that clicks OK needed no changes. Dismissal (Cancel, scrim tap, system
back) routes through the same plain `onDismiss` lambda every other sheet on this screen uses, rather
than awaiting `sheetState.hide()` — consistency with the existing convention outweighs the
animate-then-dismiss nicety for one surface.

ADR-0034's substantive decision — no instant-log fast path, a not-future-only range, the picked date
driving the duplicate guard/off-schedule gate/adaptive math — is entirely untouched. Only its Decision
section's passing description of the container as "a plain Material3 `DatePickerDialog`" is now stale;
that ADR is not amended or superseded, since nothing it actually decided has changed.

## Consequences

- Plant Detail's action-flow prompts are now uniformly bottom sheets — no more visual inconsistency
  between this picker and `WateringReasonBottomSheet`/`RescheduleReasonBottomSheet`/
  `WateringExplanationSheet`.
- No custom calendar UI was written; `DatePicker`'s day-grid/month-navigation/selection behavior is
  unchanged from ADR-0034, only rehosted.
- `AddCareLogScreen`'s picker and `RescheduleWateringDialog`'s custom-date option remain centered
  dialogs. This is a deliberate scope boundary, not an oversight — nothing here commits the app to
  converting every date picker to a bottom sheet, and a future inconsistency complaint about either of
  those two surfaces would need its own issue.
- Two details future edits to this sheet must preserve, not one: `skipPartiallyExpanded = true`, and
  keeping the OK/Cancel row pinned outside the `DatePicker`'s scrollable inner `Column`. Either one
  alone is insufficient — `skipPartiallyExpanded` prevents a *partial-expansion* clip, but only the
  pinned-row-plus-scrollable-calendar structure prevents an *oversized-content* clip on a short
  viewport (landscape, resized multi-window). Removing either reintroduces a real regression, not a
  cosmetic one.
