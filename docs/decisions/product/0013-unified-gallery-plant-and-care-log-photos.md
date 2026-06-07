# Product ADR-0013: Plant Detail gallery unifies plant photos and care-log photos

**Status**: accepted  
**Date**: 2026-06-07

## Context

Issue #290 added a `plant_photos` table for standalone plant photos and requested a gallery on the Plant Detail screen. Before this feature, the detail screen already showed a separate "Photos" section populated from care-log entries whose `photoUri` was non-null. With the new table, the question arose whether to show two separate sections (one per source) or a single merged gallery.

## Decision

The Plant Detail screen shows a **single unified gallery** combining photos from both `plant_photos` and care-log `photoUri` fields, sorted by timestamp descending. Duplicates (same URI in both sources) are deduplicated, keeping the earliest timestamp.

The previous separate "Photos" section sourced from care logs is replaced by this unified strip.

## Rationale

A single sorted timeline is simpler to scan and requires no explanation of why two separate photo sections exist. Users think in terms of "photos of my plant over time", not "photos I took via the edit screen vs. photos I attached to logs". The technical distinction between the two storage locations is an implementation detail that should not be surfaced in the UI.

## Consequences

- `PlantDetailViewModel` exposes `galleryPhotos: StateFlow<List<GalleryPhoto>>` built via `Flow.combine` of the two sources.
- `GalleryPhoto` is a lightweight projection type `(uri: String, timestamp: Long)` with no storage-layer coupling.
- `.distinctBy { it.uri }` is applied before sorting to guard against a URI appearing in both `plant_photos` and `care_logs` (e.g. from the DB migration that seeds `plant_photos` from `coverPhotoUri`).
- The `photoLogs: StateFlow<List<CareLog>>` previously on `PlantDetailViewModel` is removed.
