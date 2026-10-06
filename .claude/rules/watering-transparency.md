---
description: '"Why this date?" sheet, watering_adjustments table, interval-suggestion apply/dismiss write path, ask-before-changing-intervals toggle'
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/schedule/WateringExplanation.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/entity/WateringAdjustmentEntity.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/db/WateringAdjustmentDao.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/repository/WateringAdjustmentRepository.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/model/WateringAdjustment.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/plantdetail/WateringExplanationSheet.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/plantdetail/PlantDetailIntervalActions.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/usecase/QuickLogUseCase.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/usecase/WateringLifecycleReset.kt"
  - "app/src/test/**/WateringExplanation*.kt"
  - "app/src/test/**/*WateringAdjustment*.kt"
  - "app/src/test/**/QuickLogUseCaseInterval*.kt"
  - "app/src/test/**/MigrationTest11To12.kt"
---

# Watering transparency (#572, product ADR-0028)

Current behaviour only; the fix history is in the cited issues and `git log`.

## Two clocks for a (possibly backdated) watering (#654, #679, #716, #767)
A quick-water can be backdated (`loggedAt`). Keep the two clocks apart:
- **Observation clock = `loggedAt`:** the observed gap, the season used to de-seasonalize that gap (`deseasonalizedObservedIntervalDays(atDate = loggedAt's day)`), the freeze-window check, `Plant.updatedAt`, `WateringAdjustment.triggeredAt`.
- **Display clock = today:** any *effective* interval the user reads. `QuickLogUseCase.computeSuggestion()` takes `now` (observation) and `displayNow` (default `nowProvider()`) and uses `displayNow` for both `effectiveIntervalForDisplay()` calls. `AddCareLogViewModel.computeSuggestedInterval()` does the same with `displayNow = System.currentTimeMillis()`. Both are `internal` so JVM tests can pin the clocks separately. Otherwise a backdated log could show a spurious dialog, or silently persist a base whose today-effective value moved, bypassing the ask toggle.
- `WateringLifecycleReset.maybeBootstrap()` likewise takes `displayNow` **only** for the `seasonFn()` conversion of the written `wateringIntervalDays`. `triggeredAt`/`updatedAt` keep the historical `now`.
- **Override clearing (#679):** a WATER insert clears an active `wateringDueDateOverride` only when its `loggedAt` is at or after the previous newest watering (no prior watering counts as `Long.MIN_VALUE`, so a first watering always clears). Backfilling an old watering never discards a newer reschedule.

## Interval-suggestion write path — one choke point (#631, #644, #718, #767, technical ADR-0027)
`QuickLogUseCase.applyWateringIntervalSuggestion(plant, originalSuggestion, newInterval, suggestedBaseInterval: Double?)` is the **only** write path for the product ADR-0006 dialog's Apply. Plant Detail (`PlantDetailIntervalActions.applySuggestedInterval()` and the silent-apply branch), Calendar and Plant List all call it — never add a per-screen copy. Math tests live in `QuickLogUseCaseIntervalApplyTest`; each VM keeps only a delegation test.
- `newInterval` is **effective-space** (what the field shows). The dialog fields are pre-filled from the effective value (`pendingWateringSuggestion.effectiveIntervalDays` / `QuickWaterSuggestion.suggestedIntervalEffective`), the same number the "Suggested: N days" sentence shows.
- Writes `wateringIntervalDays = newInterval` directly. The base is written only when `!plant.pinIntervalToBase && amplitude != 0.0`; pinned/amplitude-Off plants keep `newInterval` as a literal and leave the stored base untouched.
- Base value: if `suggestedBaseInterval` is non-null (the field is untouched), persist the model's unrounded base verbatim. `null` means the user retyped the field (callers pass it via `takeIf { field == suggested }`); then derive `SeasonalWatering.deseasonalize(newInterval)` **on the Apply day**. Re-deriving a base from a rounded value amplifies the ±0.5-day rounding by 1/season (#718).
- Apply reads `nowProvider()` once and uses it for the seasonal inverse, `Plant.updatedAt` and `DIALOG_EDIT.triggeredAt`.
- The effective `newInterval` is converted down to base-space **once** (`newIntervalBaseSpace`) and reused for the base write, `confidenceAfterDialogEdit()`'s tolerance check and the row below.
- The `DIALOG_EDIT` row's `afterIntervalDays` is **base-space** (the model's accounting); it intentionally differs from the effective `wateringIntervalDays`. With seasonal watering on and the plant unpinned, `wateringIntervalDays` and `wateringBaseIntervalDays` legitimately diverge.
- Silent apply (ask toggle off) converts the raw suggestion through `effectiveWateringIntervalDaysForDisplay()` from the precise base before calling the choke point, so it commits the number the dialog would have shown.
- **Dismissal (#674):** `QuickLogUseCase.recordWateringSuggestionDismissal(plant)` is the shared confidence bump + `DIALOG_DISMISSAL` row (`before == after`, base-space via `currentAdaptiveBaseIntervalDays()`, guarded on `wateringIntervalDays != null`). All three dismiss call sites delegate to it.

## `watering_adjustments` table (product ADR-0028)
A dedicated table, not a `CareLog` replay: dismissals, manual edits and silent applies change confidence/base without any `CareLog` row.
- A row is written **every time** the model evaluates, including no-ops (`before == after`).
- Triggers (`WateringAdjustmentTrigger`):
  - `WATER_TOO_SOON`/`WATER_TOO_LATE`/`WATER_JUST_RIGHT`/`WATER_NEUTRAL` come from the WATER observation path.
  - `WATER_NOT_ATTRIBUTED` is an off-schedule watering the user declined to attribute. It wins via `AdaptiveInterval.excludedFromBaseLearning` and is distinct from on-schedule `WATER_NEUTRAL`.
  - `FROZEN_POST_REPOT` is an observation excluded by the REPOT freeze window. It is not a declined attribution.
  - `DIALOG_DISMISSAL` and `DIALOG_EDIT` come from the suggestion dialog.
  - `MANUAL_EDIT` comes from `AddEditPlantViewModel.saveEdit()` and `PlantDetailViewModel.setWateringInterval()`.
  - `REPOT_RESET`/`ROOM_CHANGE_RESET` are lifecycle resets that change confidence only (`before == after`).
  - `HISTORY_BOOTSTRAP` is the one-time cold start from watering history.
  - `CHECK_STILL_MOIST` is **historical only** (product ADR-0039). It is never written now, but old rows still render read-only in Recent adjustments.
- `getRecentForPlant(plantId, limit)` is `ORDER BY triggeredAt DESC LIMIT :limit`; Plant Detail uses `RECENT_ADJUSTMENTS_LIMIT = 5`.
- A reschedule writes no row. `recordReschedule()` writes only the override, through the column-specific `PlantDao.updateWateringDueDateOverride()` (a statement that can't touch other columns), with `updatedAt` from the injected clock.
- Schema: `MIGRATION_11_12` (DB 12). Backup v13 adds `BackupRoot.wateringAdjustments` (default empty) and `BackupSettings.askBeforeChangingIntervals` (default true); see `rules/backup.md`.

## "Ask before changing intervals" (`SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS`, default `true`)
- A plain settings key, not a feature flag, so it survives turning developer mode off; the row lives on the main Settings screen. Consulted via `PlantDetailViewModel.shouldShowIntervalDialog()`.
- **On:** the product ADR-0006 `AlertDialog`. **Off:** `applySuggestionOrPrompt()` calls the choke point directly (logged as `DIALOG_EDIT`) and emits `Event.SilentIntervalApplied(beforeIntervalDays, beforeBaseIntervalDays, afterIntervalDays)`. The snackbar's Undo calls `undoSilentIntervalApply(before…)`, which restores the captured prior values as-is (never recomputed) and writes no new row.
- Calendar/Plant List have no silent path: they always show the dialog.
- The Add Care Log save-flow suggestion (always null now that the screen is edit-only) would also go through this toggle via `PlantDetailViewModel.handleSuggestedWateringInterval()`. `NavGraph` never sets `suggestedWateringInterval` directly.

## The sheet (`WateringExplanationSheet.kt`)
- Entry: a "Why this date?" `TextButton` (`testTag("why_this_date_button")`) in the Water tab's inline-settings card, shown whenever `wateringIntervalDays != null` (or an active dormant cadence).
- Content comes from pure `WateringExplanationBuilder.build()`. It takes `nextWateringDueAt`/`lastWateredAt`/`rescheduleDeltaDays` from `PlantCareStatus` and calls the same `CareSchedule.effectiveWateringIntervalDaysForDisplay()` the schedule uses, so the sheet can't drift from the due date. `PlantDetailViewModel.wateringExplanation` combines `plant`, `careStatus`, the water-log count, `seasonalAmplitudeValue` and `recentWateringAdjustments`. Base and confidence are always populated.
- **Season row:** only when `seasonalAmplitude != 0.0 && !plant.pinIntervalToBase` (never "× 1.00"). `SeasonBand` (`SLOWER_GROWTH`/`FASTER_GROWTH`/`TRANSITIONAL`) buckets the outer/middle thirds of `[1-amplitude, 1+amplitude]`, one string per band.
- **Confidence:** `WateringConfidenceLevel` (`STILL_LEARNING`/`GETTING_THERE`/`DIALED_IN` = confidence 0-1/2-3/4-5). The label `Text` is the accessible content; `ConfidenceDots` are decorative. Tests assert the label, never the dot count (#420).
- **Recent adjustments:** `relativeDateText()` date, `WateringAdjustmentTrigger.labelRes()`, and `before → after` (or "unchanged").
- **Reschedule delta row (#630):** display-only mirror of the Water tab's chip (same `watering_reschedule_delta_days` plural, no tap target).
- Textual only; it does not embed `SeasonalWateringCurveChart`.
- **Dormancy (#763, product ADR-0044):**
  - `DORMANT_SUSPENDED` shows "schedule suspended" in place of the due date and hides the effective-interval and reschedule rows. Base, season, last-watered, confidence and Recent adjustments stay; `DORMANCY_EXCLUDED`/`DORMANCY_EXIT` use their existing labels.
  - A configured dormancy window makes the sheet fully expanded with a bounded inner scroll.
  - With an opted-in dormant cadence (#785, product ADR-0046), the sheet shows the active due date plus "Dormant schedule · Every N weeks", labels the model detail "growing-season schedule", and hides the season row.
