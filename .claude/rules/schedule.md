---
description: CareSchedule status computation and adaptive watering-interval rules
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/schedule/**/*"
  - "app/src/test/**/schedule/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/util/DateUtils.kt"
---

> Computed seasonal watering factor (`seasonalAmplitude`/`hemisphere` params on `computeStatus()`,
> #569, product ADR-0026) has its own file: `.claude/rules/seasonal-watering.md`.

# CareSchedule rules

Pure business logic. Calendar-day comparisons via `Long.toLocalDate()` — never millisecond division
(technical ADR-0013). `daysBetween()` uses `ChronoUnit.DAYS`.

## computeStatus()
- **Watering** — never-watered plant with an interval set is **due today** (`nextWateringDueAt = now`,
  `isDueSoon = true`), stays due-today (never drifts overdue) until the first WATER log; an existing
  `wateringDueDateOverride` still wins via `maxOf()`.
- **Fertilizing** — never-fertilized plant with an interval becomes due at
  `createdAt + FIRST_FERTILIZE_GRACE_DAYS` (30, named const), then overdue by normal date math.
- **Repotting** — first-due for a never-repotted plant is `createdAt + interval` (private generic
  `extendedCareDueAt()`), so a newly added plant isn't flagged immediately. Populates
  `nextRepottingDueAt`/`isRepottingOverdue`/`isRepottingDueSoon`/`lastRepottedAt` (all defaulted, existing
  callers unaffected). See product ADR-0022 (#232).
- **Custom reminders** — unbounded per plant, so unlike repotting they're a `List<CustomReminderStatus>`
  (`PlantCareStatus.customReminderStatuses`), not scalar fields. `computeStatus()` takes a `customReminders:
  List<CustomReminder> = emptyList()` param; each reminder reuses `extendedCareDueAt()` independently, but
  anchored to **the reminder's own `createdAt`**, not the plant's — reminders are commonly added long after
  plant creation, so a fresh reminder must not be flagged overdue immediately (#560 follow-up). See technical
  ADR-0019 (#232).
- No interval configured → "Not scheduled".

## computeSuggestedInterval() — adaptive watering (product ADR-0006)
- `JUST_RIGHT` suggests when `actualIntervalDays != currentInterval`.
- `TOO_SOON` uses `currentInterval` as base when the user watered early (`actual < stored`).
- `TOO_LATE` clamps to `min(actual, stored)`.
- Final result `.coerceAtLeast(1)` — two same-day waterings (`actual == 0`) can't yield a 0-day suggestion (#446).
- Flow: after a WATER log, `AddCareLogViewModel` computes `actualIntervalDays` from the last two waterings and
  passes the result back via `savedStateHandle["suggestedWateringInterval"]`; the detail screen shows a modal
  editable `AlertDialog` (product ADR-0006, supersedes product ADR-0005).

## computeAdaptiveInterval() — multiplicative + confidence-weighted (product ADR-0025, technical ADR-0021, #568)
Behind `FeatureFlagRegistry.ADAPTIVE_WATERING` (`adaptive_watering`, default off) — the legacy
`computeSuggestedInterval()` above is untouched and stays the flag-off path; call sites (`AddCareLogViewModel`,
`QuickLogUseCase`) branch on the flag before choosing which pure function to call.
- `target = observed × multiplier(feedback)` (1.25 TOO_SOON / 1.00 JUST_RIGHT / 0.82 TOO_LATE); `base = base +
  g(confidence) × (target − base)`. Gain table indexed 0-5: `[0.60, 0.45, 0.35, 0.28, 0.22, 0.15]`.
- Clamped to ±40% per step (of the pre-step base, before rounding — rounding to a whole day can add up to another
  half-day on top of the 40%, an accepted quantization artifact, not a bug) then `.coerceIn(1, 180)` overall.
- `Plant.wateringConfidence: Int?` (0-5, `null` = never adapted) is the only new column (DB v10, `MIGRATION_9_10`;
  backup schema v11). `CareSchedule.correctionStreak(recentFeedback)` derives the same-direction run from
  `CareLogRepository.getRecentWaterings(plantId, limit = 3)` (most-recent-first) — **never** cached on a column
  (editing/deleting a past WATER log must be reflected on the next adaptation with no stale cache).
- Confidence never rises from the feedback chip's value alone — only from gap agreement (observed within
  `GAP_AGREEMENT_TOLERANCE` = 15% of the current base) or a dialog dismissal (capped at
  `DISMISSAL_CONFIDENCE_CEILING` = 3, never lowers an already-higher value); it falls (`-2`, floored 0) when
  `correctionStreak()` shows `abs(streak) >= 2`. First observation (`wateringConfidence == null`) bootstraps to 0
  without evaluating a transition, but still corrects `base` at the confidence-0 gain.
- Manual-edit semantics differ by surface: an AddEditPlant interval edit is a full reset (`confidence = 0`,
  `AddEditPlantViewModel.save()`); editing the number inside the ADR-0006 dialog before Apply reuses
  `GAP_AGREEMENT_TOLERANCE` — within it, normal rules; outside it, `-2` floored at 0 (`PlantDetailViewModel
  .applySuggestedInterval()`/`.dismissSuggestedInterval()`, the latter routed from the dialog's Dismiss button and
  `onDismissRequest`, not `clearSuggestedInterval()` — that one stays a silent no-side-effect state clear for the
  stale-suggestion cleanup `LaunchedEffect`).
- `CareScheduleAdaptiveReplayTest` is the pure-JVM replay harness (scenarios 1a/1b/2/3a/3b/4); do not alter the
  multipliers/gain table to chase different convergence numbers — see technical ADR-0021 for the corrected
  convergence figures (5 obs/46 days obedient, 2 obs/28 days autonomous) and why "confidence never reaches 5" in
  scenario 3b is a known-unreachable bound from the originating issue thread, not a bug in this implementation.
- `AddCareLogViewModel`/`QuickLogUseCase` de-seasonalize the observed gap before calling
  `computeAdaptiveInterval()` when `SEASONAL_WATERING` is on (`observedBase = observedGap / season(dateOfGap)`,
  #569, product ADR-0026) — `computeAdaptiveInterval()` itself is unaware of seasonality; only its
  `observedIntervalDays` input is patched at the call site. See `.claude/rules/seasonal-watering.md`.
- **`feedback: WateringFeedback?`** — widened to nullable (#570, product ADR-0027): the WATER-log feedback chip
  collapsed to one optional flag, making `null` the dominant case. `null` maps to `NEUTRAL_TARGET_MULTIPLIER`
  (1.00, same value as JUST_RIGHT's — `target = observed` verbatim) at a gain capped by
  `NEUTRAL_OBSERVATION_GAIN` (0.15) — a ceiling on the existing gain, not a second learning rate. Confidence still
  updates normally on gap agreement for a null-feedback observation; only the `base` correction is throttled.
- **The off-schedule exclusion (#586, product ADR-0030)** narrows that further: `gain = 0.0` when `feedback == null`
  **and** the gap disagrees with `currentBaseIntervalDays` (`isUnattributedOffScheduleObservation()`), reported back
  as `AdaptiveInterval.excludedFromBaseLearning`. Off-schedule is exactly when the reason prompt appears, so a `null`
  there means the user was asked why and declined to attribute it — a pre-emptive holiday watering marked "just my
  timing" must not shorten `base` through the passive channel. **Derived inside the pure function, never passed in**:
  a boolean threaded through call sites is one a caller can forget, and this way the rule holds identically for the
  quick-log sheets, AddCareLog, a bulk log, and the notification's "Watered" action. Consequence: `base` now only ever
  moves on explicit attribution or on an on-schedule nudge inside the tolerance band. Confidence is deliberately not
  separately suppressed — an off-schedule gap disagrees with the prediction whatever the reason, so it simply
  doesn't rise. Call sites map an excluded result to `WateringAdjustmentTrigger.WATER_NOT_ATTRIBUTED`.
- **`PlantCareStatus.isWateringGapLong`** (#586 follow-up) is the *direction* of an off-schedule gap —
  `true` once the observed gap has run longer than the effective interval. Only meaningful while
  `isWateringOnSchedule` is false, and it selects the reason prompt's late wording ("Why was it late?" /
  "It was dry by then" / "Forgot, or no time") over the early one ("Why now?" / "The plant needed it" /
  "Just my schedule"). Same two bits in either direction — about the plant, or about you — so this is
  wording only and ADR-0030's mapping is untouched. Derived in `wateringGapRanLong()` from the same
  gap-vs-effective-interval comparison as `isWateringOnSchedule`, **never** from `isOverdue`: the latter
  measures against the due date, which a `wateringDueDateOverride` moves, so a deferred plant can be
  not-overdue while its gap has still run long.
- **`PlantCareStatus.isWateringOnSchedule`** (#586) is the UI half of the same test, computed in `computeStatus()`
  via `wateringOnScheduleNow()`: raw observed gap vs the *effective* (seasonal) interval, where the model compares
  the de-seasonalized gap vs `base` — the same test, since `observed / season` vs `base` is `observed` vs
  `base × season`. That equivalence is what lets "was the prompt shown" be derived rather than persisted. `true`
  (no prompt) when there's no interval or no previous watering.
- **Lifecycle resets + cold-start bootstrap (#571)** — see `domain/usecase/WateringLifecycleReset.kt`.
  A `REPOT` care log or a qualifying `Plant.room` change (any real change except blank/empty -> filled
  for the first time) resets `wateringConfidence` to 0, gated on `ADAPTIVE_WATERING` and written once
  as a side effect at log-creation/plant-save time — never derived live from querying REPOT log
  history, so editing/deleting a past REPOT log can't spuriously re-trigger a reset. A REPOT reset also
  sets `Plant.wateringFreezeUntil` (`wateringResetAt + 28 days`, room-change resets never set this) —
  `computeAdaptiveInterval(..., frozen = true)` while `now < wateringFreezeUntil` forces the same
  exclusion treatment as an unattributed off-schedule observation (gain 0, `excludedFromBaseLearning`),
  reusing #586's mechanism rather than inventing a second one; confidence still updates normally
  (evidence about the schedule regardless of why `base` is excluded). `CareSchedule.bootstrapBaseInterval
  (waterLogTimestampsMs, seasonFn)` cold-starts `base`/confidence from history — `median(gap_i /
  season(date_i))` / `min(5, gapCount / 3)` — evaluated on every WATER-log adaptive observation via
  `WateringLifecycleReset.maybeBootstrap()`: once when `wateringConfidence == null` (first-ever
  observation, whole history eligible) or repeatedly while `wateringResetAt != null` (post-reset,
  eligible history bounded to `wateringFreezeUntil ?: wateringResetAt`), applying only when
  `CareSchedule.MIN_BOOTSTRAP_GAPS` (3) is met and dual-writing `wateringIntervalDays`/
  `wateringBaseIntervalDays` (mirroring `applyIntervalInternal()`'s dual-write fix) plus clearing
  `wateringResetAt` so it fires exactly once. When it fires, `adaptWateringInterval()` returns the
  pre-bootstrap interval unchanged so the ADR-0006 suggestion dialog never re-surfaces a value the
  bootstrap already silently committed.
- **`CareType.CHECK`** ("Soil still moist", #570 product ADR-0027, reached via the Reschedule reason prompt since
  #586 product ADR-0030) is a `TOO_SOON` observation fed through this same function by
  `QuickLogUseCase.recordStillMoistCheck(plant, newDueAtMillis)` — full confidence gain (it's explicit, not silent),
  and only `Plant.wateringConfidence` is persisted from the result; the suggested `intervalDays` itself is never
  silently applied. Gated on `ADAPTIVE_WATERING` only — `check_reminders` being on is orthogonal (see
  `.claude/rules/notifications.md`). The **length** of the deferral is never a model input (#586): the reason
  already decided what is learned, and `suggestedStillMoistDeferralDays()` (`newBase - observedGap`, floored at
  `DEFAULT_STILL_MOIST_DEFERRAL_DAYS` = 1) only *suggests* a date — it shares `computeStillMoistAdaptiveInterval()`
  with the real write so the two can't drift. A reschedule the user attributed to themselves ("I can't right now")
  still does **not** feed this model at all (verified by `SkipWateringReceiverTest`) — ADR-0030 keeps ADR-0029's
  posture for that half.

## DateUtils.formatRelative()
Calendar-day (`ChronoUnit.DAYS.between`) so "Last: X days ago" reflects calendar days, not a rolling 24h window
(#351). History list + Graveyard show exact dates (e.g. "Jun 10, 2026") for events > 14 days old; PlantCard chips
and Detail stats always show the relative form (#387).

## Convention reminder
Suspend `buildStatus()` runs inside a `combine {}` block — `List.map {}` takes a non-suspend lambda, so it uses a
`for` loop with `mutableListOf`. Don't refactor to `.map {}` (technical ADR-0003).
