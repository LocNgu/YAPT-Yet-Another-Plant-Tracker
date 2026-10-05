---
description: CareSchedule status computation and adaptive watering-interval rules
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/schedule/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/today/**/*"
  - "app/src/test/**/schedule/**/*"
  - "app/src/test/**/today/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/util/DateUtils.kt"
---

# CareSchedule rules

Pure business logic. Related rules files:
- Seasonal watering factor: `rules/seasonal-watering.md`.
- Fertilizing seasons: `rules/fertilizing-seasons.md`.
- Repot plans: `rules/repotting.md`.
- Care queue aggregation: `rules/care-queue.md`.

Dates:
- Calendar-day comparisons use `Long.toLocalDate()`, never millisecond division (technical ADR-0013).
- `daysBetween()` uses `ChronoUnit.DAYS`.
- Every "N days from X" due-date advance goes through `Long.plusCalendarDays()`, never `+ TimeUnit.DAYS.toMillis(n)` (DST fall-back loses a day, technical ADR-0034).

## computeStatus()
- **Watering:** a never-watered plant with an interval is **due today** (`nextWateringDueAt = now`, `isDueSoon = true`). It never drifts overdue before the first WATER log. A `wateringDueDateOverride` wins via `maxOf()`, so a reschedule can only push later.
- **Fertilizing** (#795, product ADR-0049): the raw due date is `lastFertilizedAt + interval`, or `createdAt + FIRST_FERTILIZE_GRACE_DAYS` (30) before the first log. It is then shifted by `SeasonalFertilizing.nextActiveDueAtMillis(raw, activeSeasons, hemisphere, nowDate)`:
  - All four seasons active returns the raw date unchanged.
  - **Future raw date:** kept if its own season is active, else moved to the 1st of the next month in an active season.
  - **Raw date today or earlier:** judged by *today's* season.
    - Today inactive: move to the 1st of the next active month (not due).
    - Today active: find the start of the contiguous run of active months ending this month (it may wrap the year, e.g. Autumn+Winter). Keep the raw date if it falls in that run (still overdue); otherwise the run start becomes the due date (due today in its first month, overdue after).
  - So a plant is never due during an inactive season. There is no learning, confidence or adjustment history for fertilizing.
- **Repotting** (product ADR-0022, #809, product ADR-0057):
  - The first due date for a never-repotted plant is `createdAt + interval` (via `extendedCareDueAt()`).
  - A one-off plan *replaces* the interval date outright, even when it is earlier. `isRepottingDueSoon` covers the whole season; `isRepottingOverdue` starts only after the season ends. `repottingPlanSeasonEndAt` and `repottingPlanSeason` are non-null exactly for a plan.
  - Without a plan, preferred seasons shift the interval date (time-stable, half-interval floor).
  - Details in `rules/repotting.md`. Tests: `SeasonalRepottingTest`, `CareScheduleSeasonalRepottingTest`.
- **Custom reminders** (technical ADR-0019): `PlantCareStatus.customReminderStatuses: List<CustomReminderStatus>`, from the `customReminders` param. Each reminder is anchored to **its own `createdAt`**, not the plant's, so a newly added reminder isn't immediately overdue.
- No interval → "Not scheduled".
- **Dormancy** (product ADR-0044/0046/0047/0049):
  - A configured window always suppresses fertilizing due/overdue.
  - Watering is either fully suspended (`dormantWateringIntervalDays == null`) or follows a fixed 1–12 week cadence. The cadence ignores seasonal/adaptive state, is floored at the current dormant cycle's first day, and becomes the computed schedule before the override applies.
  - `PlantCareStatus.isDormant` marks both. Only *fully suspended* plants go in the Dormant group/calendar bucket (shown on today, not counted as due, both care dates suppressed in the window); cadence plants participate normally.
  - Ordinary due dates resume immediately on exit. Dormancy-spanning waterings are excluded from learning and reason prompts.
- **On-schedule / direction (#586, #649):**
  - `isWateringOnSchedule` (`wateringOnScheduleNow()`) compares the raw observed gap with the *effective* interval, within `GAP_AGREEMENT_TOLERANCE`. That equals the model's de-seasonalized-gap-vs-base test, which is why "was the prompt shown" can be derived rather than stored. It is `true` with no interval or no previous watering.
  - `isWateringGapLong` (`wateringGapRanLong()`) gives the direction of an off-schedule gap (only meaningful while `isWateringOnSchedule` is false) and picks the reason prompt's late wording and option set (`rules/care-logging.md`). Derive it from the gap, **never** from `isOverdue`, because an override moves the due date.
  - `CareSchedule.isWateringOnScheduleAt`/`isWateringGapLongAt` are the same checks for a picked (backdated) date.
- `computedNextWateringDueAt` (the pre-override date) and `rescheduleDeltaDays` (non-null only while the override wins) are computed once in `computeWateringDue()` and never re-derived elsewhere.
- Suspend `buildStatus()` runs inside `combine {}`, so it uses a `for` loop + `mutableListOf`, never `.map {}` (technical ADR-0003).

## computeAdaptiveInterval() — multiplicative + confidence-weighted (product ADR-0025, technical ADR-0021, #568)
The only watering-suggestion path; the old ±1-day nudge and the `ADAPTIVE_WATERING` flag are gone (#655). Callers (via `AdaptiveWateringObservation`, `rules/care-logging.md`) measure the observed gap from the new log's chronological predecessor, de-seasonalize it when amplitude isn't Off (`observed / season(dateOfGap)`, product ADR-0026), and show the result in the product ADR-0006 editable dialog. The function itself knows nothing about seasons.
- **Target and gain:** `target = observed × multiplier` (1.25 TOO_SOON / 1.00 JUST_RIGHT / 0.82 TOO_LATE); `base += g(confidence) × (target − base)`, with gains `[0.60, 0.45, 0.35, 0.28, 0.22, 0.15]` for confidence 0–5.
- **Clamping:** each step is clamped to ±40% of the pre-step base, then `coerceIn(1, 180)`. Don't tune the multipliers or gains to chase convergence numbers. `CareScheduleAdaptiveReplayTest` is the replay harness, and technical ADR-0021 has the figures (scenario 3b never reaching confidence 5 is a known bound).
- **`feedback: WateringFeedback?`** (product ADR-0027): `null` uses `NEUTRAL_TARGET_MULTIPLIER` (1.00) with gain capped at `NEUTRAL_OBSERVATION_GAIN` (0.15). Confidence still updates on gap agreement.
- **Off-schedule exclusion (#586, product ADR-0030):** `feedback == null` **and** a gap outside tolerance (`isUnattributedOffScheduleObservation()`) gives gain 0 and `excludedFromBaseLearning` (→ `WATER_NOT_ATTRIBUTED`). This is **derived inside the function, never passed in**, so every surface (quick-log, Add Care Log, bulk log, the notification's Watered action) behaves the same. `base` moves only on explicit attribution or an on-schedule nudge.
- **Confidence (`Plant.wateringConfidence: Int?`, 0–5, null = never adapted):**
  - It rises only on gap agreement (within `GAP_AGREEMENT_TOLERANCE` = 15% of base) or a dialog dismissal (capped at `DISMISSAL_CONFIDENCE_CEILING` = 3, never lowered by it).
  - It drops by 2 (floor 0) when `correctionStreak()` ≥ 2 in one direction.
  - The first observation bootstraps it to 0 but still corrects `base`.
  - `correctionStreak()` is derived from `getRecentWaterings(limit = 3)` and never cached in a column, so edits and deletes take effect.
- **Manual edits:** an Add/Edit interval change resets confidence to 0. Editing the number inside the dialog applies the normal rules within tolerance, and −2 outside it. The dialog's Dismiss button and `onDismissRequest` go to `dismissSuggestedInterval()`.
- **Sub-day precision (#717/#718, technical ADR-0027):**
  - The model is `Double` end to end. `AdaptiveInterval` carries the unrounded `baseIntervalDays` (persisted) and the rounded `intervalDays` (display only). Never route the persisted base through an `Int`.
  - Never derive a base back from a rounded effective value: it amplifies the ±0.5-day residual by 1/season.
  - Without sub-day precision, a neutral correction can't move any base ≤ 26 days.
  - The whole-day rounding on top of the ±40% clamp is an accepted display artifact.
- The suggestion-dialog gate compares live *effective* values, never the stored `wateringIntervalDays` literal (`rules/seasonal-watering.md`).
- **Lifecycle resets + cold start (#571, `WateringLifecycleReset.kt`):**
  - **Resets:** a REPOT log or a real `room` change (not blank → first value) resets confidence to 0. It is written once at log/save time, never derived from REPOT history. A REPOT reset also sets `wateringFreezeUntil = wateringResetAt + 28 days`, the one deliberate fixed duration. While frozen, observations get gain 0 + `excludedFromBaseLearning` (→ `FROZEN_POST_REPOT`), and confidence still updates.
  - **Cold start:** `bootstrapBaseInterval(waterLogTimestampsMs, seasonFn)` returns `median(gap_i / season(date_i))` with confidence `min(5, gapCount / 3)`. `maybeBootstrap()` runs it on the first-ever observation, or while `wateringResetAt != null` (history bounded to `wateringFreezeUntil ?: wateringResetAt`). It needs `MIN_BOOTSTRAP_GAPS` (3), dual-writes interval + base, and clears `wateringResetAt` so it fires once. When it fires, `adaptWateringInterval()` returns the pre-bootstrap interval, so the dialog never re-offers what was silently committed.
- **Reschedule is model-neutral** (product ADR-0039): `recordReschedule()` writes only the override. `CareType.CHECK` and `CHECK_STILL_MOIST` are historical only.

## DateUtils.relativeDate() / relativeDateText()
`relativeDate()` returns a `RelativeDate` sealed type, which `relativeDateText()` renders from resources. It counts calendar days (#351). History and the Graveyard show exact dates for events more than 14 days old; plant cards and Detail always show the relative form (#387).
