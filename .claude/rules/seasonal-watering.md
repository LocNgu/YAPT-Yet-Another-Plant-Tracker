---
description: Computed seasonal watering factor (curve, hemisphere, base interval, pin)
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/schedule/SeasonalWatering.kt"
  - "app/src/test/**/SeasonalWatering*.kt"
  - "app/src/test/**/CareScheduleSeasonal*.kt"
---

# Seasonal watering rules (#569, product ADR-0026)

Computed, not learned — see ADR-0026 for the full rationale (data sparsity + shared-shape argument
against per-month learning). This file is the mechanical reference.

## The curve
`domain/schedule/SeasonalWatering.kt` is the single pure-logic home:
- `season(date, amplitude, hemisphere) = 1 + amplitude · cos(2π · (dayOfYear − peakDay) / 365)`.
  `peakDay` = day 5 (northern); +182 for southern.
- `effectiveInterval(base, date, amplitude, hemisphere)` = `round(base × season(...))`, clamped to
  `[1, 180]` (`MIN_EFFECTIVE_INTERVAL_DAYS`/`MAX_EFFECTIVE_INTERVAL_DAYS`).
- `deseasonalize(value, date, amplitude, hemisphere)` is the inverse — used by the migration, manual
  interval edits, and Part 1's observed-gap de-seasonalization. `deseasonalizeToDays` is the
  `Int`-rounding wrapper for observed-gap callers.
- `SeasonalAmplitude` enum (`OFF`/`MILD`/`STANDARD`/`STRONG` = 0.0/0.2/0.35/0.5) is the DataStore-backed
  global setting (`SettingsKeys.SEASONAL_AMPLITUDE`, `runCatching { valueOf(...) }.getOrDefault(STANDARD)`,
  mirroring `ThemeMode`'s precedent) — lives in this file, not `ui/theme/`, because
  `MIGRATION_10_11` (`data/db`) needs it too and `ui/theme` isn't reachable from there.
- `Hemisphere` is derived from `TimeZone.getDefault().id` against a maintained allowlist of
  southern-hemisphere zone-ID prefixes (`hemisphereForTimeZoneId`/`currentHemisphere()`) — no location
  permission, no network. Unmatched/equatorial zones default northern (low-stakes: seasonality is weak
  near the equator anyway).
- `seasonalAmplitudeFlow()`/`seasonalAmplitudeOnce()` (`DataStore<Preferences>` extensions, same file)
  are the single choke point every call site reads: 0.0 whenever `FeatureFlagRegistry.SEASONAL_WATERING`
  itself is off, so "flag off" and "amplitude Off" collapse to the identical no-op path.

## Wiring into CareSchedule
`CareSchedule.computeStatus(...)` takes `seasonalAmplitude: Double = 0.0` and
`hemisphere: Hemisphere = SeasonalWatering.currentHemisphere()` — defaults preserve today's behavior
for any caller that doesn't pass them (JVM tests, etc.). `effectiveWateringIntervalDays()` (private)
is the single point that decides: `wateringIntervalDays` unchanged when `seasonalAmplitude == 0.0` or
`Plant.pinIntervalToBase`; otherwise `SeasonalWatering.effectiveInterval()` applied to
`Plant.wateringBaseIntervalDays` (falling back to the literal `wateringIntervalDays` as the base when
none was ever recorded — e.g. a plant created while the flag was off). Only watering is seasonal;
fertilizing/repotting/custom reminders are untouched. Every due-date consumer (`ReminderWorker`,
`PlantListViewModel`, `CalendarViewModel`, `PlantDetailViewModel.careStatus`) reads
`dataStore.seasonalAmplitudeFlow()`/`.seasonalAmplitudeOnce()` and passes it through — never
re-derive the season math at a call site.

## Data model
`Plant.wateringBaseIntervalDays: Double?` (season-neutral reference, `REAL` — deliberately not rounded
at rest, since only the *effective* interval is rounded) and `Plant.pinIntervalToBase: Boolean` (default
`false`) ship unconditionally regardless of the flag's state (`MIGRATION_10_11`, DB v11, `.yapt` backup
schema v12) — same posture as `wateringConfidence` in #568: toggling the flag off/on never loses state.

## Migration (`MIGRATION_10_11`, `data/db/PlantDatabase.kt`)
Not a pure `ALTER` — after adding both columns, every plant with a non-null `wateringIntervalDays` gets
`wateringBaseIntervalDays = wateringIntervalDays / season(migrationDay, STANDARD, currentHemisphere())`,
iterated row-by-row via a `Cursor` (SQLite has no `cos()`). Always uses `SeasonalAmplitude.STANDARD` —
a Room migration can't read the not-yet-chosen DataStore amplitude setting synchronously, and STANDARD
is the registry default. This makes the *effective* interval on migration day exactly equal to the
pre-migration `wateringIntervalDays`, regardless of what calendar month the migration happens to run
in — asserted by `MigrationTest10To11`. `pinIntervalToBase` defaults `false` for every existing row.

## Manual edits reset the base, mirroring Part 1's confidence-reset precedent
Both `AddEditPlantViewModel.save()` and `PlantDetailViewModel.setWateringInterval()` de-seasonalize a
newly typed/dragged interval to *today* (`base = editedValue / season(now)`) when `SEASONAL_WATERING` is
on and the plant isn't pinned — an unprompted edit is the user asserting a new baseline. When the flag
is off (amplitude reads 0.0), the prior base is preserved rather than cleared, so re-enabling the flag
later doesn't lose it. `AddEditPlantScreen`/`PlantDetailScreen` (Water tab, gated behind
`PLANT_DETAIL_TABS`) both surface a "Pin interval" `Switch` bound to `pinIntervalToBase`, gated behind
`SEASONAL_WATERING` being on.

## Interaction with Part 1's adaptive model (#568)
`AddCareLogViewModel`/`QuickLogUseCase` de-seasonalize the *observed gap* before feeding it into
`CareSchedule.computeAdaptiveInterval()` (`deseasonalizedObservedIntervalDays`), per ADR-0026's
"Interaction with Part 1" consequence — so a seasonal swing isn't misread as a permanent change in the
plant's thirst. This only patches the observed-input argument; `currentBaseIntervalDays` stays
`Plant.wateringIntervalDays` exactly as Part 1 already reads/writes it, and the legacy pre-#568
`computeSuggestedInterval()` ±1-day path is untouched (matching Part 1's own precedent of leaving that
path alone).

## Settings UI
Amplitude picker is a normal (non-Developer-section) `SettingsScreen` row, visible only while
`FeatureFlagRegistry.SEASONAL_WATERING` is on (`SettingsViewModel.seasonalAmplitude` StateFlow +
`setSeasonalAmplitude()`), takes effect immediately (StateFlow-driven, no relaunch). The flag itself
appears automatically in the Developer section's generic flag list (registry entry only, no extra UI
code needed per the `FeatureFlagRegistry` pattern).
