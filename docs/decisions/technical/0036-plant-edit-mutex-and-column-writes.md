# Technical ADR-0036: Plant Detail row writes share one lock plus a fresh read, or a column-specific UPDATE

**Status**: accepted

**Date**: 2026-10-01

## Context

Product ADR-0050 (#531) introduced `PlantDetailViewModel.intervalEditMutex`: the watering/fertilizing
interval writes take it and re-read the plant with `plantRepository.getPlantById(plantId).first()` inside
the lock, because the cached `plant` StateFlow can still hold the pre-write snapshot while a write is in
flight, and a full-row `updatePlant()` built from that snapshot silently reverts the other write. #804
moved the fertilizing-season toggle, liquid-fertilizer switch and pin-interval switch onto it. Dormancy kept
a separate `dormancyEditMutex`, and #809's repot-plan writes took `intervalEditMutex` even though they are
column-specific, so that a full-row writer that had already read the row could not put the old plan back.

#808 found the same race in every other Plant Detail write that ended in a full-row `updatePlant()` off
`plant.value` with no lock: `applySuggestedInterval`, the silent-apply branch of `applySuggestionOrPrompt`,
`undoSilentIntervalApply`, `dismissSuggestedInterval`, `revertReschedule`/`undoRevertReschedule`, and the
cover-photo writes (`saveReminderPhoto`, `savePhotoLog`, `deletePhoto`). Two more gaps surfaced:

- Dormancy's own lock did not serialize against the interval/season/pin/liquid writes, so a dormancy edit
  could revert one of them or be reverted by it.
- `quickWater()`/`quickLiquidFertilize()` write the plant row (cleared override, new confidence) and then
  call `applySuggestionOrPrompt()`; its silent-apply branch read `plant.value`, usually still the pre-log
  snapshot, and wrote the whole row back, restoring the cleared override and the old confidence. This is the
  most realistic back-to-back case.

Alternatives considered: one lock per field group (does not help, the writes share one row); replacing every
write with a column UPDATE (the apply/undo/dismiss paths change several columns plus an audit row, so a
column statement per call would not be atomic); a Room transaction around every write (the read-modify-write
spans a ViewModel-level read and the use case, and does not stop a full-row write that read before the
transaction began).

## Decision

1. **One lock, renamed `plantEditMutex`.** `intervalEditMutex` becomes `plantEditMutex` and absorbs
   `dormancyEditMutex`. It serializes the Plant Detail ViewModel's own writes to the plant row: interval,
   season, pin, liquid-fertilizer, dormancy, repot plan, suggestion apply/dismiss/undo, reschedule
   apply/revert/undo, and the cover-photo writes. The writes inside `QuickLogUseCase` behind `quickWater`,
   `quickFertilize`, `quickRepot` and `quickLiquidFertilize` are not covered (see Consequences, #872).
2. **Multi-column and audit-row writes read fresh inside the lock.** Each takes `plantEditMutex`, reads
   `getPlantById(plantId).first()` inside it, and passes that plant to the use case (or copies it for the
   write) — never `plant.value`. Undo still restores its captured values unconditionally; the guarantee is
   only that it never reverts a column it does not own.
3. **Single-column writes use a column-specific UPDATE, and still take the lock.** A statement that names
   only its own columns cannot revert a concurrent write to another column
   (`PlantDao.updateWateringDueDateOverride`, `updateRepotPlan`, and the new `updateCoverPhotoUri`, no schema
   change). The lock is still taken so a full-row writer that read the row *before* this write cannot write
   the old value of that column back. Where a decision depends on a column (reschedule revert capturing the
   previous override; `deletePhoto` deciding whether the deleted photo is the cover), it is made from a fresh
   read inside the lock.
4. **`QuickLogUseCase.saveReminderPhoto()` is the one reminder-photo save.** Plant Detail delegates to it
   like Plant List, Calendar and Care. It writes the cover through `updateCoverPhotoUri` inside its existing
   transaction, and Plant Detail wraps the call in `plantEditMutex`.
5. **`Mutex` is not reentrant.** The lock is never held across a `quickWater*`/`quickLiquidFertilize*` call:
   those go on to call `applySuggestionOrPrompt()`, which takes the lock itself. Events (`emitEvent`) are
   emitted after the lock is released, because `_events` is unbuffered and its collector can be blocked in a
   Snackbar.
6. **Photo-reference cleanup semantics are unchanged** (technical ADR-0031). `updatePlant()` deliberately
   does not fire `onPhotoReferencesRemoved` (hot path; the daily sweep covers a replaced cover), and
   `updateCoverPhotoUri()` mirrors that. Deleting a gallery photo still fires it through
   `PlantPhotoRepository.deletePhoto`.

## Consequences

- None of the listed paths can undo a concurrent Plant Detail write to a column it does not change, and the
  lock is no longer interval-specific: a new Plant Detail write to the plant row must take `plantEditMutex`
  and read fresh, or use a column-specific UPDATE under it. Product ADR-0050's lock scope is amended by this
  ADR (its Status line carries the amendment).
- The dormancy and interval controls can no longer interleave, at the cost of dormancy writes waiting behind
  an in-flight interval write (milliseconds).
- Out of scope, tracked as a follow-up (#872): full-row writes inside `QuickLogUseCase` that take a caller's
  snapshot (`persistAdaptiveState`, `clearWateringOverrideIfActive`, the backdated branch) behind
  `quickWater`/`quickFertilize`/`quickRepot`, and the Calendar and Plant List copies of apply/dismiss. They run
  outside `plantEditMutex`, so they remain exposed to the same race against a Plant Detail write.
- `QuickLogUseCase.saveReminderPhoto()` now writes only the cover column, which also hardens its Plant List,
  Calendar and Care callers against the same race.
