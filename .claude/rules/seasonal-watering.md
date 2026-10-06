---
description: Computed seasonal watering factor (curve, hemisphere, base interval, pin)
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/schedule/SeasonalWatering*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/usecase/SeasonalGraduationFixup*.kt"
  - "app/src/test/**/SeasonalWatering*.kt"
  - "app/src/test/**/SeasonalGraduationFixup*.kt"
  - "app/src/test/**/CareScheduleSeasonal*.kt"
---

# Seasonal watering rules (#569, product ADR-0026)

Computed, not learned (rationale in product ADR-0026); ships unconditionally (#656). Applies to watering only. Fertilizing uses `SeasonalFertilizing` (`rules/fertilizing-seasons.md`) with no cosine or amplitude, and repotting/custom reminders are untouched.

## The curve (`domain/schedule/SeasonalWatering.kt`)
- **Functions:**
  - `season(date, amplitude, hemisphere) = 1 + amplitude · cos(2π · (dayOfYear − peakDay) / 365)`. `peakDayOfYear(hemisphere)` is day 5 in the north and +182 in the south; it is the single source, also used by the chart caption.
  - `effectiveInterval(base, …) = round(base × season)`, clamped to `[1, 180]`.
  - `deseasonalize(value, …)` is the inverse (`deseasonalizeToDays` is its `Int` wrapper for observed gaps).
- **Amplitude:** `SeasonalAmplitude` `OFF`/`MILD`/`STANDARD`/`STRONG` = 0.0/0.2/0.35/0.5. It is a DataStore setting (`SettingsKeys.SEASONAL_AMPLITUDE`, default `STANDARD`) and lives in this file because `MIGRATION_10_11` needs it. Every call site reads it through `seasonalAmplitudeFlow()`/`seasonalAmplitudeOnce()`.
- **Hemisphere:** `currentHemisphere()` matches `TimeZone.getDefault().id` against an allowlist of southern zone prefixes. No location permission is needed; unmatched zones default to northern.

## Wiring into CareSchedule
- `computeStatus(seasonalAmplitude = 0.0, hemisphere = currentHemisphere())`. The defaults keep old behaviour for callers that don't pass them.
- The private `effectiveWateringIntervalDays()` is the single decision point: the literal `wateringIntervalDays` when amplitude is 0 or the plant is pinned, otherwise `effectiveInterval(wateringBaseIntervalDays ?: wateringIntervalDays)`.
- Every due-date consumer (`ReminderWorker`, Plant List, Calendar, Plant Detail) passes the amplitude through. Never re-derive season math at a call site.

## Two interval numbers — the recurring trap
- `Plant.wateringBaseIntervalDays: Double?` is season-neutral, `REAL`, and what the model reasons about. It is **deliberately unrounded**: never tidy it to an `Int`.
- `Plant.wateringIntervalDays: Int?` is **effective-space at every read site** (settled three times, #620/#626/#644; don't re-litigate). It is rewritten only on discrete events (manual edit, suggestion apply, bootstrap, the #702 fixup), while the curve keeps moving between them.
- So never read the stored `wateringIntervalDays` as "today's effective interval"; use `CareSchedule.effectiveWateringIntervalDaysForDisplay()`. When comparing "the interval", say which of the two numbers you mean.
- **The model's input:** `AdaptiveWateringObservation.currentAdaptiveBaseIntervalDays(plant, configured)` uses the base when amplitude is on and the plant is unpinned, else the configured literal. It also de-seasonalizes the *observed gap* before `computeAdaptiveInterval()`, so a seasonal swing isn't learned as a thirst change.
- **The suggestion-dialog gate compares live with live (#716):** `effectiveWateringIntervalDaysForDisplay()` of the base before vs. after the observation, both evaluated **today** (display clock, `rules/watering-transparency.md`). Comparing against the stored literal fired dialogs on pure calendar drift. A base that moves but rounds to the same effective value today stays silent (technical ADR-0027). `QuickWaterSuggestion.currentIntervalEffective` carries the live value to Calendar/Plant List.
  - `AdaptiveWateringObservation` owns the gate for quick-log, its only caller (Add Care Log is edit-only and never observes, technical ADR-0037). **`PlantDetailViewModel.pendingWateringSuggestion` is still a separate copy**, so a gate fix must land in both places.
  - Displayed drift is stepped: it moves when `base × season` crosses a whole-day boundary, mostly around Apr 6 / Oct 6, where the curve is steepest.

## Data model and migration
- `wateringBaseIntervalDays` and `pinIntervalToBase: Boolean` (default false) were added in `MIGRATION_10_11` (DB v11, backup v12).
- The migration isn't a pure `ALTER`: it sets `base = wateringIntervalDays / season(migrationDay, STANDARD, currentHemisphere())` row by row via a `Cursor` (SQLite has no `cos()`). It always uses STANDARD (it can't read DataStore), so migration-day effective intervals are unchanged (`MigrationTest10To11`).

## Manual edits reset the base
`AddEditPlantViewModel.save()` and `PlantDetailViewModel.setWateringInterval()` set `base = edited / season(today)` when amplitude is on and the plant is unpinned. With amplitude Off they preserve the prior base, so turning amplitude on later doesn't lose it. A "Pin interval" `Switch` (`pinIntervalToBase`) is always visible on Add/Edit and the Water tab.

## App-start reconciliation fixup (#702, `SeasonalGraduationFixup.kt`)
A one-time backfill run from app-start work. It re-anchors every unpinned plant's base to today, as the migration did, and logs `SEASONAL_GRADUATION_FIXUP` per changed plant. It is gated on `SettingsKeys.SEASONAL_BASE_GRADUATION_FIXUP_DONE`, which is device-local and excluded from backup.
- **When `DONE` latches:** only after a real pass with amplitude on and a non-empty plant list. An empty or amplitude-Off install must stay eligible for a later restore or amplitude change.
- **Fresh reads, column-specific write:** each plant is re-fetched (`getPlantById(id).first()`) before its eligibility check, and deleted plants are skipped. The write is the column-specific `PlantDao.updateWateringBaseInterval(id, base, updatedAt)`, never a full-row `.copy()`, so a concurrent UI edit can't be reverted.
- **Skip check:** the no-op test compares raw `Double`s, not rounded days. The logged row's before/after values stay rounded.
- **Legacy flag:** if the old `feature_flag_seasonal_watering` DataStore key reads `true`, the whole fixup is skipped (bases may already be correctly anchored). `DONE` is still subject to the gating above.
- **Accepted limitations, no schema to fix them:**
  - The legacy check sees only the flag's *current* value, so an ever-on-then-off flag reads as never enabled.
  - A `.yapt` restore after `DONE` is set is never reconciled. A fix would clear `DONE` when importing a pre-#656 backup schema.

## Settings and preview (#579)
- The amplitude picker is a normal Settings row (`SettingsViewModel.seasonalAmplitude`/`setSeasonalAmplitude()`) and takes effect immediately.
- `SeasonalWateringCurveChart` (`rules/chart.md`) renders under that picker and in the Water tab's settings card. It is built on pure `SeasonalWateringCurveSampler.sample(amplitude, hemisphere, referenceYear)` (one point per day). It is visualization only.
