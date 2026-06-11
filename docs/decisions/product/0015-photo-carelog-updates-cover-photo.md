# Product ADR-0015: Photo care log entries update the plant cover photo

**Status**: accepted  
**Date**: 2026-06-10  
**Supersedes**: [ADR-0012](0012-cover-photo-source-addeditplant-only.md)

## Context

ADR-0012 restricted cover-photo updates to AddEditPlant only, on the grounds that care-log photos are contextual snapshots that may not represent the plant's identity well (e.g. a wilting leaf, fertiliser pellets, roots during repotting).

Issue #304 identified that `CareType.PHOTO` is a special case: the entire purpose of a Photo care log entry is to document the plant's appearance. Unlike other care types where a photo is incidental context, a Photo entry exists solely for the image. Promoting it to the cover is the expected and desirable outcome.

## Decision

When a `CareType.PHOTO` care log is saved with a non-null `photoUri`, `PlantEntity.coverPhotoUri` is updated to that URI. This applies to both new logs and edits.

All other care log types (Water, Fertilize, Prune, Mist, Repot, Note) continue to follow ADR-0012 — their photos appear in the gallery but do not update `coverPhotoUri`.

## Rationale

The Photo care type is intentionally about capturing the plant's appearance at a point in time. A user who logs a Photo entry expects that photo to become the new face of the plant. Treating it the same as a watering photo would be surprising and inconsistent with user intent. The distinction is clear: a Photo log *is* a cover-photo update, contextualised as a care event.

## Consequences

- `AddCareLogViewModel.saveLog()` calls `plantRepository.updatePlant(plant.copy(coverPhotoUri = photoUri, ...))` when `careType == PHOTO && photoUri != null`.
- The new cover is reflected immediately in PlantDetail (hero image) and PlantCard via the existing `Plant` Flow.
- ADR-0012's constraint on other care types (Water, Fertilize, etc.) remains in effect.
