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
  `NEUTRAL_OBSERVATION_GAIN` (0.15): `gain = if (feedback == null) min(confidenceGain, NEUTRAL_OBSERVATION_GAIN)
  else confidenceGain` — a ceiling on the existing gain, not a second learning rate. Confidence still updates
  normally on gap agreement for a null-feedback observation; only the `base` correction is throttled. This is what
  keeps a single outlier gap (e.g. a 30-day holiday) with no feedback from dragging `base` as hard as an explicit
  TOO_SOON/TOO_LATE would — the existing ±40% per-step clamp covers the rest.
- **`CareType.CHECK`** ("Still moist", #570, product ADR-0027) is a `TOO_SOON` observation fed through this same
  function by `QuickLogUseCase.recordStillMoistCheck()` — full confidence gain (it's explicit, not silent), and
  only `Plant.wateringConfidence` is persisted from the result; the suggested `intervalDays` itself is never
  silently applied (no dialog exists for a notification action to show). Gated on `ADAPTIVE_WATERING` only —
  `check_reminders` being on is orthogonal (see `.claude/rules/notifications.md`). Skip watering/Reschedule
  deliberately does **not** feed this model at all (verified by `SkipWateringReceiverTest`) — see product ADR-0027.

## DateUtils.formatRelative()
Calendar-day (`ChronoUnit.DAYS.between`) so "Last: X days ago" reflects calendar days, not a rolling 24h window
(#351). History list + Graveyard show exact dates (e.g. "Jun 10, 2026") for events > 14 days old; PlantCard chips
and Detail stats always show the relative form (#387).

## Convention reminder
Suspend `buildStatus()` runs inside a `combine {}` block — `List.map {}` takes a non-suspend lambda, so it uses a
`for` loop with `mutableListOf`. Don't refactor to `.map {}` (technical ADR-0003).
