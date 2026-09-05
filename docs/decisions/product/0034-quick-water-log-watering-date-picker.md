# ADR-0034: Quick-water always opens a "Log watering" date picker (no instant-log fast path)

**Status**: accepted

**Date**: 2026-09-05

## Context

Every quick-water entry point on Plant Detail (`WateringDueActionsRow`'s Water button in both
layouts, the classic-layout watering `StatChip`, and `CombinedWaterFertilizeActionRow`/
`FertilizeDueActionRow`'s liquid-fertilizer path) always logged the WATER care event with the
current timestamp. A user who watered yesterday but forgot to log it had no way to backdate a
quick-log — only the full manual entry flow (`AddCareLogScreen`, reached via the "Log care" FAB)
has a date picker, defeating the reason someone reaches for quick-water in the first place (fewer
taps).

### Considered and rejected

**A. Long-press on the Water button opens a date picker; plain tap is unchanged.** The initial
spec resolution. Long-press keeps today's one-tap "log now" behavior completely untouched and adds
backdating as a secondary, discoverable-if-you-know-it-exists affordance —
`Modifier.combinedClickable(onLongClick = ...)` with an `onLongClickLabel` for TalkBack
discoverability. Rejected on human review: long-press is visually clean but not discoverable — a
user who doesn't already know long-press exists on this button (there is no visual affordance
hinting at it) has no way to find the backdating feature at all, silently reproducing the exact
problem this issue exists to fix, just for a different subset of users.

**B. A small secondary affordance next to the button (e.g. a calendar icon) opens the date
picker.** Considered as a discoverable alternative to long-press. Rejected as this issue's
resolution: adds a second visible control per action row (Water, and the combined
Water+Fertilize action), which conflicts with `WateringDueActionsRow`'s existing two-button budget
(ADR-0029 narrowed this row to two buttons deliberately) and would need its own layout/accessibility
design pass. Left as a candidate if the chosen approach (C) proves to have real friction in
practice.

## Decision

**A plain tap on Water (or its liquid-fertilizer counterpart) always opens a "Log watering" date
picker first, pre-selected to today.** There is no more instant "log now on tap" fast path.
Confirming the dialog with today selected reproduces the old one-tap-log behavior in exactly one
extra confirm tap; picking an earlier (never future) date backdates the log. This applies
uniformly to every quick-water surface on Plant Detail listed above. Plain (non-liquid)
`quickFertilize()` is unchanged — fertilizing alone has no adaptive-interval/reason-prompt concept
for a chosen date to feed into. `AddCareLogScreen`'s existing manual date picker, and the Plant
List/Calendar quick-water surfaces, are unchanged/out of scope — those are one-tap card/list-row
actions with no existing long-press affordance and would need their own interaction-design pass
(flagged as a candidate follow-up issue).

The one extra confirm tap is an accepted, deliberate cost: discoverability for the backdating
feature (option A's failure) outweighs preserving the zero-friction instant tap for the common
"log now" case. `LogWateringDatePickerDialog` (`ui/screens/plantdetail/WateringDueActions.kt`) is
a plain Material3 `DatePickerDialog`, not-future-only via `SelectableDates`
(`TodayOrEarlierSelectableDates`, the mirror-image of `RescheduleWateringDialog`'s existing
`TodayOrLaterSelectableDates` — this control backfills a *past* date, Reschedule defers to a
*future* one) — distinct from `RescheduleWateringDialog`, which sets `Plant
.wateringDueDateOverride` on the *next due date*, not the logged event's date; the two remain
conceptually and visually separate, no shared composable beyond both using `DatePicker`.

The picked date drives, consistently:
- The same-day duplicate guard (`CareLogRepository.hasLogOfTypeOnDay`, keyed off the picked day, not
  "today").
- The `CareLog.loggedAt` write itself, with only the calendar date changing — time-of-day comes
  from the current wall clock, mirroring `AddCareLogScreen`'s own new-log date-picker `Calendar`
  field-copy pattern (never local midnight).
- The off-schedule reason-prompt gate (`WateringReasonBottomSheet`), re-evaluated against the
  picked date via new `CareSchedule.isWateringOnScheduleAt`/`isWateringGapLongAt` public wrappers —
  the exact same `GAP_AGREEMENT_TOLERANCE`-based comparison `PlantCareStatus
  .isWateringOnSchedule`/`isWateringGapLong` already use against real "now", generalized to any
  candidate date rather than introducing a second notion of "close enough". Picking today
  reproduces `PlantCareStatus`'s own precomputed result exactly, since the underlying gap
  comparison is calendar-day granular (`CareSchedule.daysBetween`).
- The adaptive-interval gap math (`CareSchedule.computeAdaptiveInterval`'s inputs, via
  `QuickLogUseCase.adaptWateringInterval()`/`computeSuggestion()`) — an explicit `loggedAt: Long =
  System.currentTimeMillis()` parameter now threads through `QuickLogUseCase
  .quickWaterWithReason()`/`quickLiquidFertilizeWithReason()`, replacing every internal
  `now = System.currentTimeMillis()` those functions and their callees used to compute
  independently, so none of the above four consumers can drift from one another. See
  `.claude/rules/watering-transparency.md`'s Follow-up (#654) note for the full call-graph detail.

The `WateringReason` → `wateringFeedback` mapping itself (ADR-0030/ADR-0033) is unchanged — only
the gap's two endpoints move from `(now, lastWateredAt)` to `(chosenDate, lastWateredAt)`.

## Consequences

- Every quick-water action on Plant Detail costs one more tap than before, even in the common
  "log now, on schedule" case. Accepted: the alternative (long-press) was already tried and
  rejected for failing the actual goal (discoverable backdating).
- A backdated quick-water log can now retroactively feed the adaptive-watering model as if it had
  been evaluated on that earlier date (duplicate guard, freeze-window check, and
  `WateringAdjustment.triggeredAt` all use the chosen date) — this mirrors the existing precedent
  that a REPOT log's lifecycle-reset anchor is the log's own `loggedAt`, "not necessarily 'now'"
  (`WateringLifecycleReset.applyRepotReset`), rather than inventing a new rule.
- Plant List and Calendar's quick-water surfaces remain "now"-only pending their own
  interaction-design pass — flagged as a candidate follow-up issue, not silently deferred.
- No schema change — this only changes which timestamp is threaded through already-existing
  `QuickLogUseCase`/`CareSchedule` write and comparison paths, not what is stored.
