---
description: "Why this date?" sheet, watering_adjustments table, ask-before-changing-intervals toggle
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/schedule/WateringExplanation.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/entity/WateringAdjustmentEntity.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/db/WateringAdjustmentDao.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/repository/WateringAdjustmentRepository.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/model/WateringAdjustment.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/plantdetail/WateringExplanationSheet.kt"
  - "app/src/test/**/WateringExplanation*.kt"
  - "app/src/test/**/*WateringAdjustment*.kt"
  - "app/src/test/**/MigrationTest11To12.kt"
---

# "Why this date?" watering transparency sheet (#572, product ADR-0028)

## Bug fix that gates everything else (also #572)
`PlantDetailViewModel.applySuggestedInterval()` (the ADR-0006 dialog's Apply button — the only place
#568's adaptive suggestion is ever committed) now dual-writes `wateringBaseIntervalDays` alongside
`wateringIntervalDays`, mirroring `setWateringInterval()`'s existing manual-edit dual-write — see
`.claude/rules/seasonal-watering.md`'s "Interaction with Part 1" section for the read-side half
(`currentAdaptiveBaseIntervalDays`). Before this fix, applying a suggestion with `SEASONAL_WATERING` on
and the plant unpinned silently never moved the due date.

**Follow-up (#626):** the dual-write above only fixed `wateringBaseIntervalDays`; `applyIntervalInternal()`
was still writing the raw base-space `newInterval` straight into the *literal* `wateringIntervalDays`
field, even though every other read site (the dialog's "currently" figure, the Water tab slider/"every N
days" text, `WateringExplanationBuilder`) treats `wateringIntervalDays` as an effective, seasonally-adjusted
value — silently drifting the literal interval a little further on every single apply. `applyIntervalInternal()`
now runs `newInterval` through `CareSchedule.effectiveWateringIntervalDaysForDisplay()` (the same
conversion `pendingWateringSuggestion` already applies for display) before writing it, so
`wateringIntervalDays` and `wateringBaseIntervalDays` genuinely diverge from each other going forward
whenever `SEASONAL_WATERING` is on and the plant is unpinned — that divergence is now correct, not a bug.
The `DIALOG_EDIT` `WateringAdjustment` row's `afterIntervalDays` deliberately stays the raw base-space
`newInterval` (unchanged) — it's the model's base-space accounting, not the user-facing effective value,
and the two are meant to differ. `Event.SilentIntervalApplied` and `undoSilentIntervalApply()` both carry
the plant's actual prior `wateringIntervalDays`/`wateringBaseIntervalDays`, captured once inside
`applyIntervalInternal()` and threaded straight through — undo restores them as-is rather than
recomputing either (recomputing `wateringBaseIntervalDays` from `beforeIntervalDays` relied on
`beforeIntervalDays` coincidentally already being base-space, which stopped being true once this fix
landed).

**Follow-up (#631):** `CalendarViewModel.applySuggestedInterval()` and `PlantListViewModel
.applySuggestedIntervalFromList()` each carried their own independent copy of this write path and had
never received either the #572 or #626 fix above — they wrote the raw base-space suggestion straight
into the literal `wateringIntervalDays` and never touched `wateringBaseIntervalDays` at all. The fix
extracts the write path out of `PlantDetailViewModel.applyIntervalInternal()` into
`QuickLogUseCase.applyWateringIntervalSuggestion(plant, originalSuggestion, newInterval):
QuickLogUseCase.IntervalApplyResult` — the single choke point all three suggestion dialogs (Plant
Detail, Calendar, Plant List) now call, so this class of bug can't recur independently on any one
screen again. `PlantDetailViewModel.applyIntervalInternal()` is now a thin delegation to this function;
its own `Event.SilentIntervalApplied`/`undoSilentIntervalApply()` wrapping (the "ask before changing
intervals" flow below) stays Plant-Detail-specific, since Calendar/Plant List have no silent-apply/undo
equivalent and always show the ADR-0006 dialog unconditionally. Math-correctness tests for the write
path itself live in `QuickLogUseCaseIntervalApplyTest`; each ViewModel keeps only a thin
delegation/smoke test verifying it calls the shared function with the right arguments.

**Follow-up (#644):** the three ADR-0006 dialogs' editable text fields were pre-filled from the raw
base-space suggestion (`suggestedWateringInterval`/`QuickWaterSuggestion.suggestedInterval`) while the
dialog's own "Suggested: N days" sentence showed the *effective* (seasonally-converted) value from the
same suggestion — two different numbers presented as one "suggestion", and accepting the untouched field
silently re-derived a third number on Apply (the field's raw value re-run through the base→effective
conversion). `applyWateringIntervalSuggestion(plant, originalSuggestion, newInterval)`'s `newInterval`
parameter is now **effective-space**, matching what the field shows/submits — `PlantDetailScreen`'s
`intervalFieldText` is pre-filled from `pendingWateringSuggestion.effectiveIntervalDays`, and
`CalendarScreen`/`PlantListScreen`'s from `QuickWaterSuggestion.suggestedIntervalEffective`, all three
already-existing fields that previously drove only the dialog's sentence, never the box. The write path
inverted accordingly: `newInterval` is written to `wateringIntervalDays` **directly** (no re-conversion —
that used to be #626's fix, now folded into the derivation below), and `wateringBaseIntervalDays` is
derived from it via `SeasonalWatering.deseasonalize()` — the inverse of `effectiveWateringIntervalDaysForDisplay()`'s
multiplication, mirroring `PlantDetailViewModel`'s existing `deseasonalizedBaseOrNull()`/
`setWateringInterval()` manual-edit precedent — gated on the same `!plant.pinIntervalToBase && amplitude
!= 0.0` condition as before (#584 review round 2 still applies: pinned/zero-amplitude plants keep
`newInterval` as a literal and leave the stored base untouched). `originalSuggestion` is unchanged,
still base-space (this class's own raw adaptive suggestion) — `confidenceAfterDialogEdit()`'s tolerance
check now converts the effective `newInterval` back down to base-space once (`newIntervalBaseSpace`,
reused for both the base write and the `DIALOG_EDIT` row's `afterIntervalDays`) rather than converting
`originalSuggestion` up, since that conversion is already needed regardless of the confidence check.
`PlantDetailViewModel.applySuggestionOrPrompt()`'s silent-apply branch (no dialog shown) now converts its
own raw suggestion through `effectiveWateringIntervalDaysForDisplay()` before calling
`applyIntervalInternal()`, mirroring `pendingWateringSuggestion`'s conversion, so a silent apply commits
the same number the dialog would have shown/pre-filled had it appeared. The `DIALOG_EDIT` row's
`afterIntervalDays` still deliberately stays base-space (unchanged posture from #626) — only the value
it's derived from changed.

## `watering_adjustments` table (`data/entity/WateringAdjustmentEntity.kt`, `data/db/WateringAdjustmentDao.kt`,
`data/repository/WateringAdjustmentRepository.kt`)
A dedicated table, not a `CareLog` replay (product ADR-0028) — a dialog dismissal, a manual edit, or a
silently-applied suggestion all change `wateringConfidence`/base without ever writing a `CareLog` row,
so a pure replay would misrepresent history. `WateringAdjustmentTrigger` (`domain/model/WateringAdjustment.kt`):
`WATER_TOO_SOON`/`WATER_TOO_LATE`/`WATER_JUST_RIGHT`/`WATER_NEUTRAL`/`WATER_NOT_ATTRIBUTED` (from
`QuickLogUseCase.adaptWateringInterval()`/`AddCareLogViewModel.adaptWateringInterval()`, keyed off the feedback
param — `WATER_TOO_SOON` reachable since #649 (product ADR-0033) via the late-direction reason prompt's
`WateringReason.SOIL_STILL_MOIST` → `TOO_SOON`, not only via `CHECK_STILL_MOIST`'s CHECK-log path — plus
`AdaptiveInterval.excludedFromBaseLearning`, which wins and selects `WATER_NOT_ATTRIBUTED`: an
off-schedule watering the user declined to attribute, #586 product ADR-0030, distinct from `WATER_NEUTRAL`'s
on-schedule "nothing to change" so the sheet can explain a row where nothing moved), `CHECK_STILL_MOIST`
(`QuickLogUseCase.recordStillMoistAdaptiveObservation()`, now reached from the Reschedule reason prompt as well as
the notification action), `DIALOG_DISMISSAL`
(`PlantDetailViewModel.dismissSuggestedInterval()`, `before == after`), `DIALOG_EDIT`
(`QuickLogUseCase.applyWateringIntervalSuggestion()` — shared by the Plant Detail dialog's Apply
button/silent-apply path and the Calendar/Plant List dialogs, #631), `MANUAL_EDIT`
(`AddEditPlantViewModel.saveEdit()`, `PlantDetailViewModel
.setWateringInterval()`), and (#571) `REPOT_RESET`/`ROOM_CHANGE_RESET` (the lifecycle-reset triggers —
`WateringLifecycleReset.applyRepotReset()` and `AddEditPlantViewModel.saveEdit()`'s room-diff check;
`beforeIntervalDays == afterIntervalDays` always, since a reset changes confidence, not the interval
itself), `FROZEN_POST_REPOT` (a WATER/CHECK observation excluded from base-learning by the REPOT freeze
window — distinct from `WATER_NOT_ATTRIBUTED` so the sheet doesn't misrepresent an automatic freeze as
a declined attribution), and `HISTORY_BOOTSTRAP` (the one-time cold-start from watering history,
`WateringLifecycleReset.maybeBootstrap()`). A row is written **every time one of these is evaluated while
`ADAPTIVE_WATERING` is on**, including a no-op observation (`before == after`) — that's still evidence
the model considered. Gated on `ADAPTIVE_WATERING` only, matching where `wateringConfidence` itself is
written; ships unconditionally regardless of the flag's state (same posture as `wateringConfidence`).
`WateringAdjustmentRepository.getRecentForPlant(plantId, limit)` is `ORDER BY triggeredAt DESC LIMIT
:limit` — same "collapse to N most recent" posture as care history (`PlantDetailViewModel
.RECENT_ADJUSTMENTS_LIMIT` = 5).

**Schema**: `MIGRATION_11_12`, `PlantDatabase.DB_VERSION` 11→12, `app/schemas/.../12.json`. `.yapt`
backup schema v12→v13: `BackupRoot.wateringAdjustments: List<BackupWateringAdjustment>` (default
`emptyList()`) + `BackupSettings.askBeforeChangingIntervals: Boolean` (default `true`) — see
`.claude/rules/backup.md`.

## "Ask before changing intervals" (`SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS`, default `true`)
A plain settings key, not a `FeatureFlagRegistry` entry — survives disabling developer mode. Only
consulted when `ADAPTIVE_WATERING` is on (`PlantDetailViewModel.shouldShowIntervalDialog()`); inert
otherwise, so the ADR-0006 dialog stays unconditional for anyone not on the adaptive model.
- **On** (default): today's ADR-0006 `AlertDialog`, byte-for-byte unchanged.
- **Off**: `PlantDetailViewModel.applySuggestionOrPrompt()` calls `applyIntervalInternal()` directly
  (same dual-write, logged as `DIALOG_EDIT`) and emits `Event.SilentIntervalApplied(beforeIntervalDays,
  beforeBaseIntervalDays, afterIntervalDays)` — `afterIntervalDays` is the effective value actually
  written (#626), not the raw suggestion; `before*` are the plant's actual prior values, not derived.
  `PlantDetailScreen` shows a `Snackbar` (`R.string.interval_auto_applied_snackbar` +
  `R.string.snackbar_undo`, mirroring `PlantListScreen`'s archive-undo convention) whose action calls
  `undoSilentIntervalApply(beforeIntervalDays, beforeBaseIntervalDays)` — a plain revert restoring both
  captured values as-is, not a new `WateringAdjustment` row.
- Settings UI row lives on the main Settings screen (not Developer section), visible only while
  `ADAPTIVE_WATERING` is on — mirrors the seasonal-amplitude picker's visibility pattern.
- The `AddCareLogScreen` save-flow suggestion (routed through `NavGraph`'s `savedStateHandle`) goes
  through the same toggle via `PlantDetailViewModel.handleSuggestedWateringInterval()` — `NavGraph`
  never sets `vm.suggestedWateringInterval.value` directly anymore.

## The sheet (`ui/screens/plantdetail/WateringExplanationSheet.kt`)
Entry point: a "Why this date?" `TextButton` (`testTag("why_this_date_button")`) in the Water tab's
inline-settings card (product ADR-0023), visible whenever `plant.wateringIntervalDays != null`. Content
built by `WateringExplanationBuilder.build()` (`domain/schedule/WateringExplanation.kt`) — a pure
function that takes `nextWateringDueAt`/`lastWateredAt` from `PlantCareStatus` (already computed by
`CareSchedule.computeStatus`) rather than recomputing them, and calls the same
`CareSchedule.effectiveWateringIntervalDaysForDisplay()` public wrapper the due-date math itself uses
for "Watering every N days" — the sheet's numbers cannot drift from the schedule by construction.
`PlantDetailViewModel.wateringExplanation: StateFlow<WateringExplanation?>` combines `plant`,
`careStatus`, `waterLogCount` (derived from `careLogs`), `adaptiveWateringEnabled`,
`seasonalAmplitudeValue`, and `recentWateringAdjustments`.

- `adaptiveWateringEnabled == false` → only `effectiveIntervalDays`/`nextWateringDueAt`/`lastWateredAt`
  populated; `baseIntervalDays`/`season`/`confidenceLevel`/`recentAdjustments` all null/empty — no rows
  invented.
- Season row (`WateringExplanationSeason`) shown only when `seasonalAmplitude != 0.0 &&
  !plant.pinIntervalToBase` — hidden entirely (never `× 1.00`) when amplitude is Off or pinned.
  `SeasonBand` (`SLOWER_GROWTH`/`FASTER_GROWTH`/`TRANSITIONAL`) is a 3-way bucket of the outer/middle
  thirds of `[1-amplitude, 1+amplitude]` — "one string per band, not per month" per the issue.
- `WateringConfidenceLevel` (`STILL_LEARNING`/`GETTING_THERE`/`DIALED_IN`, buckets of `wateringConfidence`
  0-1/2-3/4-5) is the sheet's accessible content for confidence — the dots (`ConfidenceDots`) are
  decorative Compose `Box`es with no semantics of their own; the label `Text` is what a Compose UI test
  must assert (#420 — never dot count via tree structure).
- Recent adjustments: date via `DateUtils.formatRelative()`, trigger via `WateringAdjustmentTrigger
  .labelRes()` (`ui/util/EnumResources.kt`), and `before → after` (or "unchanged" when equal).
- Deliberately textual/numeric only — does not embed `SeasonalWateringCurveChart` (#579), which lives
  one card away in the same inline-settings card and shows only the multiplier curve, no day-based
  numbers.
- **Reschedule delta row (#630):** `WateringExplanation.rescheduleDeltaDays` mirrors
  `PlantCareStatus.rescheduleDeltaDays` verbatim (populated regardless of `adaptiveWateringEnabled`,
  same as `nextWateringDueAt`/`lastWateredAt`) — a plain `ExplanationRow` using the same
  `watering_reschedule_delta_days` plural as the actionable chip outside the sheet. Display-only, no
  tap target here — see `.claude/rules/plant-detail.md`'s "Reschedule delta chip + revert" for the
  chip/revert flow itself.
