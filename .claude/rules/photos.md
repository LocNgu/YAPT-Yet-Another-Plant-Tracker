---
description: Photo capture/compression, CameraPhotoState, orphaned photo sweeper
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/util/ImageUtils*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/util/OrphanPhotoSweeper*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/components/CameraPhotoState*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/worker/*Photo*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/repository/PlantPhotoRepository*.kt"
  - "app/src/test/**/ImageUtils*.kt"
  - "app/src/test/**/*Photo*.kt"
---

# Photos

## Storage (#309, product ADR-0041)
- **Camera captures** are bounded to 1920 px on the longest side, JPEG q80 (`ImageUtils`). Existing private captures migrate once via `ExistingCameraPhotoCompressionWorker`.
- **`CameraPhotoState`** keeps the in-flight capture file/URI in `rememberSaveable` via `CameraPhotoState.Saver` (#706). `TakePicture` returns only a `Boolean`, so the target must survive Activity recreation on the app's side. New camera surfaces use `rememberCameraPhotoState()`, never their own launcher.
- **Gallery-picked originals are never modified.** The export dialog's "Optimize photos" checkbox (default off) compresses only the archive copies; PNG stays PNG, and any failure falls back to the original bytes.

## Orphaned photo reclamation (#736, #559, technical ADR-0031)
- **The sweep:** `util/OrphanPhotoSweeper.kt` reconciles `filesDir/images` and `filesDir/restored_photos` against every referenced URI (`plants.coverPhotoUri` incl. archived ∪ `care_logs.photoUri` ∪ `plant_photos.uri`).
  - It deletes only regular top-level files that are unreferenced **and** older than `MIN_AGE_MILLIS` (24 h). The age check protects in-flight captures and stale `.processing` temp files.
  - The whole pass runs in one `withTransaction {}`.
- **Never eager-delete a photo file where its row is deleted:** one URI is often referenced from more than one table.
- **When it runs:** `worker/OrphanPhotoCleanupWorker` runs daily (`schedulePeriodic`, registered in `MainActivity`), plus one-shot:
  - after any write that removes a photo reference, via the `onPhotoReferencesRemoved` callback injected into `PlantRepository`/`CareLogRepository`/`PlantPhotoRepository` (wired through `YaptApplication.scheduleOrphanPhotoCleanup()`, a no-op in `TestYaptApplication`);
  - after a successful `.yapt` restore (`BackupManager.onImportCompleted`).
- `PlantRepository.updatePlant` is deliberately not hooked (hot path); the daily sweep covers a replaced cover photo.
