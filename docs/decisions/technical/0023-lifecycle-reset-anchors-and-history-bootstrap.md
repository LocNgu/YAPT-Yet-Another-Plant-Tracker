# Technical ADR-0023: Lifecycle reset anchors and the history-bootstrap sampling rule

**Status**: accepted

**Date**: 2026-08-30

## Context

#571 (Part 4 of 5, split from #285) adds two related pieces to the adaptive watering model (#568,
technical ADR-0021): lifecycle events (repotting, moving a plant to a different room) that should reset
`Plant.wateringConfidence`, and a one-time cold-start estimate of `Plant.wateringBaseIntervalDays` from
a plant's existing watering history when there wasn't one yet.

Two design questions needed a real answer rather than an obvious one: how to make "a REPOT log resets
confidence" resistant to the log later being edited or deleted (AC3), and how to decide when the
cold-start bootstrap should run without adding a "when was `adaptive_watering` turned on for this
plant" concept the app doesn't otherwise track.

## Decision

### Reset anchors are plain columns, written once, never derived live

`Plant.wateringResetAt: Long?` and `Plant.wateringFreezeUntil: Long?` are written as a side effect at
the exact moment a REPOT log is created (`WateringLifecycleReset.applyRepotReset()`, called from both
`AddCareLogViewModel.saveLog()` and `QuickLogUseCase.quickLog()`'s bulk-REPOT path) or a qualifying
`Plant.room` change is saved (`AddEditPlantViewModel.saveEdit()`). Neither is ever recomputed by
querying REPOT log history at read time — this mirrors technical ADR-0021's reasoning for rejecting a
derived `lastCorrectionDirection` column, but in the opposite direction: ADR-0021 rejected a *cached*
value because logs can be edited/deleted and the cache would go stale; here a *derived* value would be
just as wrong, since editing or deleting a past REPOT log must **not** retroactively change whether a
reset already happened. The two rules point at different mechanisms for the same underlying reason
(editing/deleting history must not change already-committed behavior).

`wateringResetAt` bounds "which gaps count as post-reset" for the bootstrap check; `wateringFreezeUntil`
(REPOT-only, `wateringResetAt + 28 days`, never set on a room-change reset) additionally gates live
per-observation exclusion. Room changes get the first without the second, by design (spec clarification):
a moved plant relies on the ordinary confidence-0 gain ramp-up, not a freeze.

### The freeze reuses #586's exclusion mechanism rather than adding a second one

A frozen observation is treated exactly like `WATER_NOT_ATTRIBUTED` (product ADR-0030) inside
`CareSchedule.computeAdaptiveInterval()`: `frozen: Boolean = false` is OR'd into the existing `excluded`
check, so a frozen WATER/CHECK log gets `excludedFromBaseLearning = true` (gain 0 on `base`) while
confidence still updates normally from gap agreement/streak — identical to how an unattributed
off-schedule watering is treated, and for the same reason (confidence is evidence about the schedule
regardless of *why* an observation doesn't count toward `base`). The `WateringAdjustmentTrigger` label
is `FROZEN_POST_REPOT`, not `WATER_NOT_ATTRIBUTED`, purely so "Recent adjustments" doesn't claim the
user was asked and declined — they were never asked.

### The bootstrap opportunity hooks into the existing `wateringConfidence == null` first-observation branch

ADR-0021 already noted that `computeAdaptiveInterval`'s `currentConfidence == null` bootstrap-to-zero
branch "stays bootstrap-friendly for #571's future history-bootstrap work." This is that hook:
`QuickLogUseCase`/`AddCareLogViewModel`'s `adaptWateringInterval()` checks, before calling
`computeAdaptiveInterval()` at all, whether this WATER log is the plant's first-ever adaptive
observation (`wateringConfidence == null`, using the plant's whole history) or a pending post-reset
opportunity (`wateringResetAt != null`, using only history at/after the freeze boundary). There is no
"flag was just toggled on" event to listen for — YAPT has no per-plant subscription to a global
DataStore flag — so "the first time this plant's adaptive code path runs while the flag is on" is used
as the practical proxy for "the flag was just enabled for this plant." This has one accepted
consequence: a plant is not retroactively bootstrapped the instant the flag flips on in Settings; it is
bootstrapped the next time a WATER log is added for it. Given the flag only matters when the user is
actively logging care anyway, this was judged an acceptable trade-off over adding a startup migration
pass over every plant.

When the bootstrap fires, `adaptWateringInterval()` returns the pre-bootstrap `currentInterval`
unchanged rather than also running the incremental per-step correction on top of a value the bootstrap
just cold-started, and rather than surfacing the ADR-0006 suggestion dialog for a change that was
already silently committed (mirroring how `PlantDetailViewModel.applyIntervalInternal()`'s dual-write
already keeps `wateringIntervalDays`/`wateringBaseIntervalDays` in sync so the due date can't silently
fail to move).

### `bootstrapBaseInterval` is a pure function operating on bare timestamps

`CareSchedule.bootstrapBaseInterval(waterLogTimestampsMs: List<Long>, seasonFn: (LocalDate) -> Double)`
takes plain `Long` timestamps rather than `CareLog` domain objects, matching every other pure function
in `CareSchedule` (`daysBetween`, `computeStatus`'s internals) — the caller is responsible for filtering
to WATER-type logs and to the post-reset boundary before calling it. It always computes a result given
≥ 2 timestamps (even a single gap), leaving the ≥ 3-gap application threshold
(`CareSchedule.MIN_BOOTSTRAP_GAPS`) as a caller-side decision — this keeps the function itself fully
testable at every sample size named in the acceptance criteria (empty, one gap, spread across seasons,
an outlier, all-same-month) without conflating "can this be computed" with "should this be applied."

## Consequences

- `Plant.wateringResetAt`/`Plant.wateringFreezeUntil` ship unconditionally regardless of
  `adaptive_watering` flag state (`MIGRATION_12_13`, DB v12→13; `.yapt` backup schema v13→v14),
  matching `wateringConfidence`'s and `wateringBaseIntervalDays`'s precedent: flipping the flag off and
  back on never loses in-progress reset/freeze state.
- `WateringLifecycleReset` is duplicated as thin call-site glue in `QuickLogUseCase` and
  `AddCareLogViewModel` (`maybeApplyHistoryBootstrap`/`seasonFnFor`), matching this codebase's existing
  precedent of duplicating `adaptWateringInterval`/`currentAdaptiveBaseIntervalDays`/
  `deseasonalizedObservedIntervalDays` between those two files rather than introducing a shared
  dependency between a `ViewModel` and a use case class.
- A REPOT/room-change reset and a history bootstrap are asked-for `WateringAdjustment` rows
  (`REPOT_RESET`/`ROOM_CHANGE_RESET`/`FROZEN_POST_REPOT`/`HISTORY_BOOTSTRAP`), all with
  `beforeIntervalDays == afterIntervalDays` for the two reset triggers (a reset changes confidence, not
  the interval) — no new UI, "Recent adjustments" (#572) already renders the "unchanged" case correctly.
- Future work touching `computeAdaptiveInterval()`'s exclusion logic should treat `frozen` and
  `isUnattributedOffScheduleObservation()` as two independent inputs to one `excluded` decision, not
  merge them — they are surfaced with different `WateringAdjustmentTrigger` values on purpose.
