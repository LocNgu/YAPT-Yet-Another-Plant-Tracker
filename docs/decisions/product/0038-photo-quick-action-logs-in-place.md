# ADR-0038: Photo quick action logs in place

**Status**: accepted

**Date**: 2026-09-12

## Context

#658 gave the Photo tab (behind `PLANT_DETAIL_TABS`) an always-visible action button, but it only
navigated to `AddCareLogScreen` with `CareType.PHOTO` preselected — the generic add-log form still owned
date entry, image source selection, and the actual save. #694 asked for the same date-first treatment
Repot got: pick a date, then act, without leaving Plant Detail.

Repot already reuses `QuickLogUseCase.quickLog()` for its side effects, so giving it a date picker was a
straightforward parameter thread-through (mirroring #654's `loggedAt` pattern on `quickWaterWithReason`).
Photo has no equivalent shared use-case method — `AddCareLogViewModel.saveLog()`'s PHOTO branch is the
only place a PHOTO `CareLog` is written today (besides the unrelated photo-reminder flow's
`saveReminderPhoto()`) — so the same question had to be answered explicitly: does the Photo tab's action
stay a navigation into that canonical form, or does it become a self-contained quick action like Repot?

### Considered and rejected

**Collect date + source in a sheet, then navigate to `AddCareLogScreen` with both preselected.** Keeps
the notes field and the single canonical PHOTO-log code path. Rejected: it needs new nav arguments
threaded through `Screen`/`NavGraph`/`AddCareLogViewModel` for a value (the date) that flow already knows
how to ask for itself, and it stumbles on partial completion — if the user backs out of image selection
after picking a date, they land on a half-filled add-log form with no photo and an edited date, a worse
dead end than dismissing a sheet.

**Give Photo a `QuickLogUseCase` method paralleling `quickLog(REPOT)`.** Rejected: `quickLog()`'s shape
(a `CareType`, a same-day duplicate guard, an optional paired-fertilizer WATER insert) doesn't fit
PHOTO — a photo is never same-day-duplicate-guarded, has no interval/reset side effect, and instead needs
an image `Uri` as an argument the other `quickLog()` call sites never pass. Bending the shared method to
fit would complicate every other caller for one care type's sake.

## Decision

**The Photo tab's Add-photo sheet writes its `CareLog` directly from `PlantDetailViewModel`, never
navigating to `AddCareLogScreen`.** `AddPhotoBottomSheet` is one `ModalBottomSheet` combining a date row
(defaulting to today, editable in place via the shared `CareDatePickerContent`) with **Take photo** /
**Choose from gallery**. Whichever source returns an image, `PlantDetailViewModel.savePhotoLog(uri,
loggedAt)` writes the PHOTO `CareLog` at the picked date and updates the plant's cover photo — matching
`AddCareLogViewModel.saveLog()`'s PHOTO-branch side effects (`CareLog` + cover-photo write), **except**
it never inserts a `plant_photos` row. `saveReminderPhoto()` (the unrelated photo-reminder flow) does
insert one, but the unified `PhotoGallery` already merges `plant_photos` with care-log photos (technical
ADR-0015) — writing both here would list the same image twice in the Photo tab. `Plant.updatedAt` stays
real wall-clock time even when the photo itself is backdated, matching every other cover-photo write on
this screen.

The trade this makes explicit: the quick path has **no notes field**. `AddCareLogScreen` remains the
canonical full-entry flow for a PHOTO log with notes — its route, `careType` preselection argument, and
the `+` FAB all keep working exactly as before this issue. Nothing about that flow changed.

The date picker itself is not duplicated: `LogWateringDatePickerDialog`'s body was split into
`CareDatePickerContent` (the actual `DatePicker` + Cancel/OK row) and `CareDatePickerBottomSheet` (a
`ModalBottomSheet` wrapper), preserving product ADR-0037's two load-bearing details
(`skipPartiallyExpanded = true`, and the `DatePicker` in its own `weight(1f, fill = false)` scrollable
inner `Column` with the button row pinned outside it) in one place. Repot's own date picker uses
`CareDatePickerBottomSheet` directly; the Add-photo sheet swaps `CareDatePickerContent` in as an internal
content state of its own single sheet, so two `ModalBottomSheet`s are never nested.

## Consequences

- Photo logging from the tab is now symmetric with Repot: pick a date, then act, no intermediate
  navigation, no half-filled form to abandon.
- The Photo tab's quick path permanently has no notes field — a user who wants to annotate a photo must
  use the `+` FAB's canonical `AddCareLogScreen` flow instead. This is accepted, not a gap to close later.
- `PlantDetailViewModel` gained direct write access to a PHOTO `CareLog` (`savePhotoLog`), a second write
  path alongside `AddCareLogViewModel.saveLog()`'s — both must be kept in sync if PHOTO's side effects
  ever change (e.g. a future field beyond cover-photo/CareLog), the same duplication risk `quickLog()`'s
  REPOT path already accepts relative to the manual Add Care Log form for REPOT.
- ADR-0034 and ADR-0037 are not amended or superseded — this decision only extends where a date is
  collected and what a PHOTO log's side effects are for one entry point; both ADRs' own decisions
  (backdating semantics, the shared sheet's structural requirements) are preserved verbatim.
