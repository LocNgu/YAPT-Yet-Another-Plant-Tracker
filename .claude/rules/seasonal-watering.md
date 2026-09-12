---
description: Computed seasonal watering factor (curve, hemisphere, base interval, pin)
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/schedule/SeasonalWatering.kt"
  - "app/src/test/**/SeasonalWatering*.kt"
  - "app/src/test/**/CareScheduleSeasonal*.kt"
---

# Seasonal watering rules (#569, product ADR-0026)

`SEASONAL_WATERING` graduated (#656) — the curve and the amplitude picker ship unconditionally; no
registry entry. Computed, not learned — see ADR-0026 for the full rationale (data sparsity +
shared-shape argument against per-month learning). This file is the mechanical reference.

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
  are the single choke point every call site reads: the user's `SettingsKeys.SEASONAL_AMPLITUDE`
  preference, defaulting to `SeasonalAmplitude.STANDARD` when unset.

## Wiring into CareSchedule
`CareSchedule.computeStatus(...)` takes `seasonalAmplitude: Double = 0.0` and
`hemisphere: Hemisphere = SeasonalWatering.currentHemisphere()` — defaults preserve today's behavior
for any caller that doesn't pass them (JVM tests, etc.). `effectiveWateringIntervalDays()` (private)
is the single point that decides: `wateringIntervalDays` unchanged when `seasonalAmplitude == 0.0` (i.e.
`SeasonalAmplitude.OFF`) or `Plant.pinIntervalToBase`; otherwise `SeasonalWatering.effectiveInterval()`
applied to `Plant.wateringBaseIntervalDays` (falling back to the literal `wateringIntervalDays` as the
base when none was ever recorded — e.g. a plant created before the seasonal curve shipped). Only
watering is seasonal; fertilizing/repotting/custom reminders are untouched. Every due-date consumer
(`ReminderWorker`, `PlantListViewModel`, `CalendarViewModel`, `PlantDetailViewModel.careStatus`) reads
`dataStore.seasonalAmplitudeFlow()`/`.seasonalAmplitudeOnce()` and passes it through — never
re-derive the season math at a call site.

## Data model
`Plant.wateringBaseIntervalDays: Double?` (season-neutral reference, `REAL` — deliberately not rounded
at rest, since only the *effective* interval is rounded) and `Plant.pinIntervalToBase: Boolean` (default
`false`) ship unconditionally (`MIGRATION_10_11`, DB v11, `.yapt` backup schema v12) — same posture as
`wateringConfidence` in #568.

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
newly typed/dragged interval to *today* (`base = editedValue / season(now)`) when amplitude isn't Off
and the plant isn't pinned — an unprompted edit is the user asserting a new baseline. When amplitude
reads Off, the prior base is preserved rather than cleared, so choosing a non-Off amplitude later
doesn't lose it. `AddEditPlantScreen`/`PlantDetailScreen` (Water tab, gated behind
`PLANT_DETAIL_TABS`) both surface a "Pin interval" `Switch` bound to `pinIntervalToBase`, always visible.

## Interaction with Part 1's adaptive model (#568, amended #572)
`AddCareLogViewModel`/`QuickLogUseCase` de-seasonalize the *observed gap* before feeding it into
`CareSchedule.computeAdaptiveInterval()` (`deseasonalizedObservedIntervalDays`), per ADR-0026's
"Interaction with Part 1" consequence — so a seasonal swing isn't misread as a permanent change in the
plant's thirst. The legacy pre-#568 `computeSuggestedInterval()` ±1-day path is untouched (matching
Part 1's own precedent of leaving that path alone).

`currentBaseIntervalDays` no longer stays `Plant.wateringIntervalDays` unconditionally (that was a bug,
fixed in #572/product ADR-0028): each of `QuickLogUseCase`/`AddCareLogViewModel`'s private
`currentAdaptiveBaseIntervalDays(plant, configuredIntervalDays)` helper reads season-neutral
`Plant.wateringBaseIntervalDays` instead, whenever amplitude isn't Off and the plant isn't
pinned — otherwise (amplitude Off, or pinned) it's unchanged, `configuredIntervalDays`
verbatim. Prior to this fix every call site fed the model a value that only ever updated on a manual
edit, silently diverging from what `CareSchedule.computeStatus()` was actually using for the due date
whenever amplitude wasn't Off. See `.claude/rules/watering-transparency.md` for the write-side half of
the same fix (`applySuggestedInterval()`'s dual-write) and the `watering_adjustments` table this bug
fix feeds.

## App-start reconciliation fixup (#702)
Graduating `SEASONAL_WATERING` (#656) removed the flag check from `seasonalAmplitudeFlow()`/
`seasonalAmplitudeOnce()`, which used to hard-return `0.0` while the dev-mode flag was off (the default
for every install). Every write path above that dual-writes `wateringBaseIntervalDays` only does so when
`amplitude != 0.0`, so on any install that never enabled the flag, `wateringBaseIntervalDays` stayed
frozen at whatever `MIGRATION_10_11` set it to while the literal `wateringIntervalDays` kept moving on
every subsequent edit/suggestion-apply — `CareSchedule` started multiplying that stale, frozen base by
the seasonal curve for real due-date math once amplitude started reading the real preference.
`domain/usecase/SeasonalGraduationFixup.kt` is a one-time app-start backfill (triggered from
`YaptApplication.onCreate()`, gated on `SettingsKeys.SEASONAL_BASE_GRADUATION_FIXUP_DONE` — a
device-local flag excluded from `.yapt` backup, mirroring `DEVELOPER_MODE_ENABLED`'s precedent, not
`ASK_BEFORE_CHANGING_INTERVALS`'s) that re-anchors every unpinned plant's `wateringBaseIntervalDays` to
today, exactly like `MIGRATION_10_11` anchored migration day — a no-op for a pinned plant, a plant with
no `wateringIntervalDays`, amplitude Off, or a plant whose base is already in sync. Logs
`WateringAdjustmentTrigger.SEASONAL_GRADUATION_FIXUP` per actually-changed plant — a distinct trigger
from `HISTORY_BOOTSTRAP` since the two reconcile from different sources (a stale column vs. replayed
watering-log history). Not a schema change — no new column, no migration/DB version bump.

**Hardening follow-up (#703 review, same PR):** four gaps found on the initial version:
- **`maybeRun()`'s `DONE` flag is only set when `request.amplitude != 0.0 && request.plants.isNotEmpty()`**
  — not unconditionally after every call. A brand-new install has an empty plant list (nothing to
  iterate yet, not "verified correct"), and amplitude Off has nothing to de-seasonalize; marking done in
  either case would permanently block a later `.yapt` restore (which can import pre-graduation, stale
  bases) or a later switch to a non-Off amplitude from ever being reconciled. The flag only latches once
  a real pass over a non-empty, amplitude-on install has actually happened.
- **`run()` re-fetches each plant fresh via `PlantRepository.getPlantById(id).first()`** immediately
  before evaluating eligibility and writing, rather than trusting the `FixupRequest.plants` snapshot
  the caller took — this runs asynchronously from `onCreate()` on a background dispatcher while the UI
  can concurrently edit/archive/quick-log the same plants, so a stale-snapshot `.copy()` could silently
  revert an unrelated concurrent write. Same bug class as `QuickLogUseCase`'s `clearWateringOverrideIfActive()`
  fix (`.claude/rules/watering-transparency.md`'s "#612/#613/#614" note) and `AddEditPlantViewModel
  .saveEdit()`'s `getPlantById(...).first()` precedent. A plant deleted since the snapshot (fetch
  returns `null`) is skipped.
- **The no-op/skip check compares raw, unrounded `Double`s (`beforeBase == newBase`), not rounded ints**
  — two bases that round to the same day count (e.g. `7.0` vs `7.4`) can still diverge meaningfully once
  multiplied by the seasonal curve, so rounding before comparing could mask a real, permanently-missed
  correction (the `DONE` flag never gives it a second chance). `beforeIntervalDays`/`afterIntervalDays`
  on the logged `WateringAdjustment` row stay rounded ints — only the skip *decision* uses raw values.
- **A legacy `feature_flag_seasonal_watering` DataStore boolean (the pre-#656 `SEASONAL_WATERING` flag's
  key, never deleted by removing it from `FeatureFlagRegistry` — DataStore doesn't garbage-collect keys
  code stops referencing) having ever been `true` skips the entire fixup for every plant on that install**,
  read via a private literal key lookup local to this fixup (not reintroduced into the registry). If that
  flag was ever on, the pre-graduation write paths were already correctly dual-writing
  `wateringBaseIntervalDays` for whatever plants were touched while it was, and this fixup has no way to
  tell a genuinely-stale base apart from one already correctly anchored to some other, unrecorded edit
  day. This is a deliberate, permanent "can't safely auto-fix this install" decision (the `DONE` flag is
  still marked, subject to the same non-empty/amplitude-on gating above) — an affected user self-corrects
  a genuinely-stale plant by making one real edit to its interval, which now dual-writes correctly.

## Settings UI
Amplitude picker is a normal (non-Developer-section) `SettingsScreen` row, always visible
(`SettingsViewModel.seasonalAmplitude` StateFlow + `setSeasonalAmplitude()`), takes effect immediately
(StateFlow-driven, no relaunch).

## Preview chart (#579)
`SeasonalWateringCurveChart` (`ui/components/`, see `.claude/rules/chart.md` for the Vico internals) renders
directly under the Settings amplitude picker and in the Plant Detail Water tab's inline settings card, so the
effect of Off/Mild/Standard/Strong is visible before committing to a setting. `domain/schedule
/SeasonalWateringCurveSampler.kt` is the pure sampling function it's built on (`sample(amplitude, hemisphere,
referenceYear)` → one `SeasonalCurvePoint` per calendar day) — a thin wrapper around `SeasonalWatering.season()`,
JVM-tested sibling to `SeasonalWateringTest`/`CareScheduleSeasonalTest`. `season()` itself now calls `SeasonalWatering.peakDayOfYear(hemisphere)` for its peak-day conditional (single
source of truth); the preview chart's Settings-only hemisphere caption also calls it to name the peak month. Visualization-only — never changes `CareSchedule.computeStatus()` or `season()`'s own math.
