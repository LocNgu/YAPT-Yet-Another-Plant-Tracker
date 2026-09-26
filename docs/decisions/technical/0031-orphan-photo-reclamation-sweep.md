# Technical ADR-0031: Reclaim orphaned photo files via a scheduled sweep, not an eager delete

**Status**: accepted

**Date**: 2026-09-26

## Context

Nothing ever deletes an image file from `filesDir/images` (in-app camera captures) or
`filesDir/restored_photos` (`.yapt` restore output) once the last database row pointing at it is gone
(#736, #559). Over time this leaks storage, and since `filesDir` (unlike `externalCacheDir`) is inside
Android Auto Backup by default, it also silently eats into the app's 25 MB backup quota.

The obvious first idea — delete the file directly wherever its owning row is deleted
(`CareLogRepository.deleteLog`, `PlantPhotoRepository.deletePhoto`, a plant's `coverPhotoUri` on
`PlantRepository.updatePlant`) — is unsafe here. A single file URI is frequently referenced from more
than one of the three tables that can point at a photo: a photo log writes both `care_logs.photoUri`
**and** a `plant_photos` row for the same file, and that same URI may also be a plant's
`coverPhotoUri`. Deleting eagerly at any one row's own deletion site risks deleting a file another
still-live row depends on.

## Decision

A reconciliation sweep (`util/OrphanPhotoSweeper.kt`), not an eager per-row delete:

- **Referenced set** = `plants.coverPhotoUri` (including archived plants — an archived plant's cover is
  still a live reference) ∪ `care_logs.photoUri` ∪ `plant_photos.uri`. Each raw string is normalized to
  a canonical on-disk `File` (bare path, `file://`, or our own `content://<applicationId>.fileprovider/
  plant_images/…` authority); any other `content://` URI (gallery-picker) normalizes to `null` and is
  never treated as ours to delete. Normalization is a pure, plain-JVM-testable function —
  `packageName`/`filesDir` are injected rather than read from a live `Context`.
- **Swept directories**: only `filesDir/images` and `filesDir/restored_photos`, and only regular files
  at the top level of each — no recursion into subdirectories, and nothing outside those two roots is
  ever touched.
- **24-hour age floor** (`OrphanPhotoSweeper.MIN_AGE_MILLIS`) before an unreferenced file is eligible
  for deletion. This is the guard against deleting an in-flight capture
  (`CameraPhotoState.pendingCameraFile`, not yet written to any row — the issue's own suggestion was
  1 hour, widened here since a captured photo can sit unsaved in the Add Log / Add Plant form while the
  app is backgrounded, and a longer wait only delays reclaiming space, never risks correctness) and the
  same floor also protects — then eventually reclaims — a stale `*.processing` temp file left behind by
  `ImageUtils.compressCameraImage()` after a crash mid-compression.
- **One transaction, not a probe-then-delete window.** The referenced-set read and every delete happen
  inside a single `PlantDatabase.withTransaction {}` call, so no concurrent Room write can land between
  "read the referenced set" and "delete a file that set didn't include."
- **Scheduling via WorkManager, never `Application.onCreate`.** `worker/OrphanPhotoCleanupWorker.kt`
  registers a `KEEP` daily periodic job (`MainActivity`, next to
  `ExistingCameraPhotoCompressionWorker`) plus a `KEEP` one-shot job fired after any write that removes
  a photo reference (`PlantRepository`/`CareLogRepository`/`PlantPhotoRepository`'s injected
  `onPhotoReferencesRemoved` callback, mirroring `QuickLogUseCase`'s existing `onWaterLogged`
  injection) and after a successful `.yapt` restore (`BackupManager.onImportCompleted`) — the latter so
  a second restore's leftover first-restore `restored_photos` files get reclaimed rather than
  accumulating indefinitely.

## Consequences

- A deleted photo's file is reclaimed on the next sweep (within a day, or near-immediately after the
  triggering write's one-shot job runs), not synchronously at delete time — an accepted latency trade
  for correctness against the multi-table reference problem above.
- `PlantRepository.updatePlant` is deliberately **not** hooked to this callback despite being able to
  orphan a cover photo (replacing it) — it is the hottest write path in the app (every field edit, every
  interval tap), and the periodic daily sweep already covers that case within its own age floor.
- `TestYaptApplication.scheduleOrphanPhotoCleanup()` is overridden to a no-op so Robolectric unit tests
  that delete plants/logs/photos through these repositories never touch a real, uninitialized
  `WorkManager`.
- No Room schema change — every query this sweep needs (`plants.coverPhotoUri`,
  `care_logs.photoUri`, `plant_photos.uri`) already existed as columns; only new read-only DAO queries
  were added.
