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
**Photo storage** (#309, product ADR-0041) — in-app camera captures are bounded to 1920 px longest side, JPEG q80 (`ImageUtils`; existing private captures migrate once via `ExistingCameraPhotoCompressionWorker`). The shared `CameraPhotoState` keeps its in-flight capture file/URI in `rememberSaveable` via `CameraPhotoState.Saver` (#706) — `TakePicture` returns only a `Boolean`, so the target must survive Activity recreation on the app's side; new camera surfaces use `rememberCameraPhotoState()`, never their own launcher. Gallery-picked originals are never modified; the export dialog's "Optimize photos" checkbox (default off) compresses only the archive copies, PNG stays PNG, and any failure falls back to the original bytes.

## Orphaned photo reclamation (#736, #559, technical ADR-0031)
**Orphaned photo reclamation** (#736, #559, technical ADR-0031) — `util/OrphanPhotoSweeper.kt` reconciles `filesDir/images` and `filesDir/restored_photos` against the referenced set (`plants.coverPhotoUri` incl. archived ∪ `care_logs.photoUri` ∪ `plant_photos.uri`), deleting only regular top-level files that are both unreferenced and older than `OrphanPhotoSweeper.MIN_AGE_MILLIS` (24h, protects in-flight captures and stale `.processing` temp files), all inside one `withTransaction {}`. Never eager-delete a photo file at its own row's deletion site — one URI is often referenced from more than one table. `worker/OrphanPhotoCleanupWorker` runs it daily (`schedulePeriodic`, registered in `MainActivity`) plus one-shot after any write that removes a photo reference — `PlantRepository`/`CareLogRepository`/`PlantPhotoRepository`'s injected `onPhotoReferencesRemoved` callback (mirrors `QuickLogUseCase`'s `onWaterLogged`), wired through `YaptApplication.scheduleOrphanPhotoCleanup()` (no-op in `TestYaptApplication`) — and after a successful `.yapt` restore (`BackupManager.onImportCompleted`). `PlantRepository.updatePlant` is deliberately not hooked (hot path); the periodic sweep covers cover-photo replacement.
