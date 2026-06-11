# Product ADR-0012: Cover photo updates only from AddEditPlant, not from care-log photos

**Status**: superseded by [ADR-0015](0015-photo-carelog-updates-cover-photo.md)  
**Date**: 2026-06-07

## Context

Issue #290 introduced a per-plant photo gallery. Two sources of photos feed the gallery: standalone photos added in AddEditPlant, and photos attached to care-log entries (watering, fertilising, etc.). The question arose: when should the plant's cover photo (`coverPhotoUri`) — the hero image shown on PlantCard and PlantDetail — be updated?

Three options were considered:

1. Update cover to the most recent photo from either source (plant or care-log).
2. Update cover only when a photo is added via AddEditPlant.
3. Let the user explicitly choose a cover from the gallery.

## Decision

The cover photo is updated **only when a photo is added via AddEditPlant** (`addPhoto()` sets `coverPhotoUri` to the new URI). Care-log photos appear in the gallery but do **not** update `coverPhotoUri`.

Option 3 (user picks cover) was deferred as a follow-up (issue #302).

## Rationale

Care-log photos are contextual snapshots tied to a care event — a wilting leaf before watering, fertiliser pellets, a root system during repotting. Automatically promoting such a photo to the plant's profile picture would likely produce confusing or unflattering cover images. The cover represents the plant's identity; the gallery represents its history. Keeping them editorially separate respects the different intent of each photo type.

## Consequences

- `AddCareLogViewModel` never touches `coverPhotoUri`.
- `coverPhotoUri` continues to be a field on `PlantEntity` / `Plant`; it is not derived from `plant_photos`.
- A user who adds only care-log photos will never get a cover photo unless they also add one via AddEditPlant.
- Future issue #302 tracks letting users pick any gallery photo as the cover.
