# Product ADR-0014: Adding a photo appends to the gallery; existing cover photos are migrated on upgrade

**Status**: accepted  
**Date**: 2026-06-07

## Context

Issue #290 changed the behaviour of the photo picker in AddEditPlant. Previously, picking or taking a photo replaced `coverPhotoUri` in place. With the introduction of `plant_photos`, two decisions were needed:

1. **New photos**: should picking a new photo replace the gallery (old behaviour) or append to it?
2. **Existing installations**: should plants that already have a `coverPhotoUri` show that photo in the gallery after upgrading, or should the gallery start empty?

## Decision

**Appending**: Picking or taking a photo in AddEditPlant adds a new row to `plant_photos` and updates `coverPhotoUri` to the new URI. The previous photo is preserved in the gallery. There is no in-app "replace" action for plant gallery photos.

**Migration**: `MIGRATION_3_4` seeds `plant_photos` with one row per plant whose `coverPhotoUri` is non-null, using `createdAt` as the `capturedAt` timestamp. After upgrade, existing plants immediately have a populated gallery.

## Rationale

The core motivation for #290 was that users lose historical photos. Starting with an empty gallery on upgrade would defeat the purpose for all existing users. Seeding from `coverPhotoUri` at upgrade ensures continuity: every plant that had a photo before the feature ships continues to show that photo in the gallery without any user action.

Appending rather than replacing is the whole point of the feature. The `coverPhotoUri` field is retained so the hero/card image continues to work without a gallery query.

## Consequences

- `AddEditPlantViewModel` accumulates `pendingPhotos` during an editing session and inserts them via `PlantPhotoRepository` on save.
- `MIGRATION_3_4` uses `INSERT INTO plant_photos (plantId, uri, capturedAt) SELECT id, coverPhotoUri, createdAt FROM plants WHERE coverPhotoUri IS NOT NULL`.
- The `capturedAt` for migrated rows equals the plant's `createdAt`, which may be earlier than when the photo was actually taken — this is an acceptable approximation since the exact capture time is not stored pre-migration.
- Issue #301 tracks adding a unique constraint on `(plantId, uri)` to prevent duplicate entries from repeated edit-save cycles.
