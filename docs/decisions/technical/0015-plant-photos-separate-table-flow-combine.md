# Technical ADR-0015: Plant gallery uses a separate table combined with care-log photos at query time

**Status**: accepted  
**Date**: 2026-06-07

## Context

Issue #290 required storing multiple photos per plant. Two storage approaches were considered:

1. **Extend `care_logs`**: use a new `PHOTO` care-log type (already exists) and treat all plant photos as care-log entries. The gallery would be a filtered view of `care_logs`.
2. **Separate `plant_photos` table**: a dedicated table `(id, plantId, uri, capturedAt)` for standalone plant photos; care-log photos stay in `care_logs.photoUri` as before. The gallery is assembled at query time via `Flow.combine`.

A third option — denormalising all photo URIs into a single `photos` table regardless of source — was not considered seriously given the FK and schema complexity it would introduce.

## Decision

A **separate `plant_photos` table** is used for standalone plant photos (added via AddEditPlant). Care-log photos remain in `care_logs.photoUri`. `PlantDetailViewModel` assembles the unified gallery at query time using `kotlinx.coroutines.flow.combine`:

```kotlin
combine(
    plantPhotoRepository.getPhotosForPlant(plantId),
    careLogRepository.getPhotoLogsForPlant(plantId)
) { plantPhotos, careLogPhotos ->
    (plantPhotos.map { GalleryPhoto(it.uri, it.capturedAt) } +
     careLogPhotos.mapNotNull { it.photoUri?.let { u -> GalleryPhoto(u, it.loggedAt) } })
        .distinctBy { it.uri }
        .sortedByDescending { it.timestamp }
}
```

`.distinctBy { it.uri }` guards against the same URI appearing in both sources (possible after `MIGRATION_3_4` seeds `plant_photos` from `coverPhotoUri`).

## Rationale

Forcing standalone plant photos through the care-log model would pollute `CareLogEntity` with semantically unrelated rows and make the care-history timeline harder to filter. The separate table keeps the data models clean: care logs are events; plant photos are a media collection. The `Flow.combine` merge is cheap — both queries are small per-plant room Flows — and the ViewModel already uses `combine` for care status computation.

## Consequences

- `PlantPhotoEntity` / `PlantPhotoDao` / `PlantPhotoRepository` are new, small classes following the existing pattern.
- `PlantDatabase` bumps to version 4; `MIGRATION_3_4` creates the table and index.
- Room exports a `4.json` schema file that must be committed with each DB version bump (existing convention).
- The `GalleryPhoto` projection type lives in `domain/model/` and has no Room annotations — it is purely a ViewModel/UI concern.
- Backup schema bumps to version 3; `BackupRoot.plantPhotos` defaults to `emptyList()` for backward compatibility with v2 backups.
