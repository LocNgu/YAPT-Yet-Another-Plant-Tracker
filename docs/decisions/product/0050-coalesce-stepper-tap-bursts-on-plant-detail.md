# Product ADR-0050: Coalesce a burst of −/+ taps on Plant Detail's inline interval cards

**Status**: accepted

**Date**: 2026-09-23

## Context

Product ADR-0048 shipped `SteppedSlider` and, for the Plant Detail inline watering/fertilizing cards,
had every −/+ tap commit immediately — the same `onValueChangeFinished` hook a slider release uses.
PR #798's review round 1 raised two problems with that:

1. **A stale-snapshot race.** `PlantDetailScheduleSettingsActions.setWateringInterval()`/
   `setFertilizingInterval()` built each write from the ViewModel's cached `plant` StateFlow
   (`plant.value`), which lags a write still in flight — Room's `Flow` re-emits asynchronously, not
   synchronously with `updatePlant()` returning. A rapid burst of taps could therefore launch several
   overlapping writes that all read the *same* stale `plant.value`, so the watering interval's
   `MANUAL_EDIT` audit row logged the wrong `beforeIntervalDays` (e.g. `5 → 7` instead of `6 → 7` for a
   tap sequence that actually passed through 6 on the way to 7).
2. **One row per tap.** Even once reads are correct, immediately committing every tap means a 5-tap
   burst from 7 to 12 days writes `Plant.wateringIntervalDays` five times and logs five separate
   `MANUAL_EDIT` rows (7→8, 8→9, 9→10, 10→11, 11→12) instead of one that actually describes what the
   user did (7→12). "Why this date?" → Recent adjustments would show five near-instantaneous entries
   for what was, from the user's perspective, a single edit.

Alternatives considered for (2):

- **Leave every tap committing immediately, only fix (1).** Rejected — technically correct but does
  nothing about the noisy audit trail a real stepper burst produces, which was the maintainer's explicit
  concern in the review.
- **Merge every same-day manual edit into one row retroactively** (a broader idea raised in review).
  Rejected *for this issue* — it's a materially bigger change (touching how `MANUAL_EDIT` rows are
  written or read everywhere, not just this one control) and is tracked separately as issue #799 rather
  than folded into this fix.
- **Debounce the write, coalescing a burst into one commit after a quiet window** (chosen). Keeps every
  intermediate tap visually responsive (the slider/label updates immediately) while deferring the actual
  persistence + audit row until the user stops tapping.

## Decision

`setWateringInterval(days, viaButtonTap)`/`setFertilizingInterval(days, viaButtonTap)` gain a
`viaButtonTap: Boolean = false` parameter, threaded from `SteppedSliderCallbacks.onValueChangeFinished`
(now `((viaButtonTap: Boolean) -> Unit)?` instead of a bare `(() -> Unit)?`, amending product ADR-0048's
callback shape) through `InlineIntervalSetting`'s `onIntervalChange`. Both call sites keep committing
**immediately** when `viaButtonTap` is `false` — a slider release or the enable `Switch` — matching
product ADR-0048's original behaviour for those two inputs unchanged. Only a stepper-button tap
(`viaButtonTap = true`) is coalesced:

- A tap starts (or restarts) a **1-second quiet-window timer**
  (`INTERVAL_TAP_COALESCE_WINDOW_MS`, `PlantDetailIntervalEditActions.kt`). Each subsequent tap
  within that window cancels the previous timer and starts a fresh one carrying the *new* target value
  — so only the value from the *last* tap in a burst is ever written, and the timer only fires once the
  user has stopped tapping for a full second.
- The eventual write and its `MANUAL_EDIT` row are produced by the same `writeWateringIntervalLocked()`/
  `writeFertilizingIntervalLocked()` function the immediate path also calls — there is exactly one write
  path per field, branching only on *when* it runs, not *what* it does.
- **The stale-snapshot race is fixed at that one write path**, not just for taps: both functions now
  read `plantRepository.getPlantById(plantId).first()` fresh, inside a new
  `PlantDetailViewModel.intervalEditMutex` (mirroring the existing `dormancyEditMutex` precedent for
  `setDormancyWindow()`), rather than trusting `plant.value`. Serializing every watering/fertilizing
  interval write behind one mutex means a burst's eventual coalesced write — and any immediate write
  that happens to race it — always sees the true latest persisted state, not a stale StateFlow snapshot.
- **A still-pending tap must not be lost if the screen is left mid-window.** The 1-second timer runs on
  `viewModelScope`, which AndroidX cancels *before* calling `onCleared()` on back navigation — so the
  timer's own delayed continuation can never itself perform the write once cancelled. Two things make
  the edit durable anyway: (a) the timer's *eventual* write, and the `onCleared()`-triggered flush
  described next, both execute via a new `PlantDetailViewModel.applicationScope` (backed by
  `YaptApplication`'s existing process-wide scope, passed in through `PlantDetailViewModel.Factory`),
  which outlives any one screen's `viewModelScope`; (b) `PlantDetailViewModel.onCleared()` checks
  whether a tap edit is still pending (tracked in a plain `pendingWateringTapDays`/
  `pendingFertilizingTapDays` var, updated on every tap and cleared once its write actually runs) and,
  if so, launches that same write on `applicationScope` immediately — bypassing the now-moot timer
  entirely, since AndroidX guarantees `viewModelScope` is already cancelled by the time `onCleared()`
  runs, so there is no risk of double-writing.
- An immediate commit (slider release or switch) cancels any pending tap timer first, so a stale queued
  tap value can never land after — and overwrite — a newer immediate commit.
- `SteppedSlider`'s haptic-tick tracking (`lastTickedValue`) now resyncs via `LaunchedEffect(value)`
  whenever `value` changes from outside an in-progress drag (a button tap, or any other external
  change) — fixing a case where a drag starting right after such a change would compare against a stale
  baseline left over from the drag before it.

**The dormancy cadence control keeps its taps committing immediately, unchanged from product
ADR-0048** — it already has its own pre-existing `dormancyEditMutex` + `pendingWindow` staleness guard
and writes no audit row at all, so neither problem this ADR addresses actually applies to it; adding the
same coalescing machinery there would be complexity with no corresponding bug to fix.

## Consequences

- A burst of −/+ taps on the Plant Detail watering/fertilizing cards now produces exactly one
  `Plant.wateringIntervalDays`/`fertilizingIntervalDays` write and (for watering) exactly one
  `MANUAL_EDIT` row, reflecting the value the user actually landed on — not one per intermediate tap.
- `PlantDetailViewModel` gains a constructor parameter (`applicationScope`, defaulted for the many
  existing direct-construction unit tests that don't exercise this path) and `PlantDetailViewModel
  .Factory` gains a matching required parameter, wired from `YaptApplication.applicationScope` (now
  `internal` instead of `private`) in `NavGraph.kt`.
- Add/Edit Plant's three sliders are unaffected — they have no `viaButtonTap`-driven coalescing concept
  at all (`onValueChangeFinished` stays unset there; every change still writes straight into the VM
  field, persisted together on Save), and now also pass a `stateDescription` (the same string already
  computed for each interval's header label) so a screen reader announces the current value after a
  change there too, matching the two Plant Detail cards' existing behaviour.
- Issue #799 remains open for the broader "merge all same-day manual edits into one row" idea; this fix
  only coalesces a single continuous tap burst, not edits separated by more than the 1-second window or
  edits made through other surfaces (Add/Edit Plant, Calendar, Plant List).
