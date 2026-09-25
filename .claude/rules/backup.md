---
description: .yapt backup/restore internals and backup schema version history
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/**/backup/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/**/BackupManager*.kt"
  - "app/src/androidTest/**/backup/**/*"
  - "app/src/test/**/backup/**/*"
---

# Backup / Restore rules

`.yapt` ZIP export/import via SAF; optional photo inclusion and opt-in-at-export lossy photo optimization; settings
round-trip; forward-compat warning dialog. Optimization changes only the copies written into the archive, never a
gallery-owned source image.

## Mechanics (don't regress these)
- **Export** assembles the ZIP in a `cacheDir` temp file first, then streams to the SAF destination — prevents
  broken 0 KB exports to cloud providers (technical ADR-0014, #144).
- **Restore** streams photos to `cacheDir` temp files (never into memory) to avoid OOM; temp files are tracked in a
  map before copy so `finally` always cleans up (#193/#195/#196).
- Single bulk `getAllLogs()` / `getAllReminders()` query (not N+1) — fetch once, group by `plantId` in memory,
  then `plants.flatMap { grouped[it.id].orEmpty() }`.
- `performImport` guards photo-file cleanup with a `dbCommitted` flag — written files are deleted only if the DB
  transaction has **not** committed, so a throw from `dataStore.edit`/`ReminderScheduler` after commit can't leave
  dangling URIs (#175).
- Navigation is blocked during export/import by a non-dismissable `BackupProgressDialog` (#365).
- **Export sources plants from `PlantDao.getAllPlantsIncludingArchived()`, not the active-only `getAllPlants()`**
  (#743) — archiving is soft/reversible (`getArchivedPlants()`, bulk restore, undo on the list screen), so a
  `.yapt` backup must round-trip an archived plant's full history (care logs, photos, custom reminders, plant
  issues, watering adjustments), not silently drop it. `BackupPlant.archivedAt` carries the column verbatim;
  restore writes it straight back onto `PlantEntity.archivedAt`, so a plant archived at export time comes back
  archived, and one that wasn't stays unarchived.
- **Photo `photoMapping` is write-then-map, not probe-then-map** (#743, reordered by #817) — export folds the
  candidate-photo open into the same loop that does the real zip write: for each candidate URI, `openPhoto()` is
  called exactly once, and only on success is the photo actually copied into the zip (via
  `copyOptimizedPhotoToZip()`/`buildZipPhotoName()`) and its `photoMapping[uri] = zipPath` entry added; on failure,
  `skippedPhotoCount` increments and nothing is added to the map. This is a single pass inside the same
  `ZipOutputStream.use {}` block — there is no `writePhotosToZip()` standalone function anymore, and no gap
  between "probed OK" and "written" for a photo to become unreadable in (the #817 bug class this reorder exists
  to close: a photo readable at probe time but not by the time the real write happened, which used to leave a
  dangling `photos/<uuid>_name.jpg` reference in the manifest). The JSON manifest (`backupPlants`/`backupLogs`/
  `backupPlantPhotos`/`backupRoot`/`jsonString`, all built from the now-final `photoMapping`) is constructed
  *after* this photo loop and its `backup.json` zip entry is written *last*, not first — harmless, since import
  scans all zip entries by name regardless of position. `skippedPhotoCount` therefore counts every
  `openPhoto()`-stage failure in one unified pass, not a probe-stage-only count as before #817 — an exception
  thrown during the copy itself (rather than `openPhoto()` returning `null`) still aborts the whole export
  rather than being counted, unchanged from pre-#817 behavior. `BackupResult.ExportSuccess.skippedPhotoCount`
  (default `0`, so existing 2-arg construction still compiles) surfaces the count; `SettingsScreen` shows the
  existing `backup_export_success` string when it's `0`, and a new `backup_export_success_with_skipped_photos`
  plural (modeled on `bulk_snackbar_logged_with_skipped`) otherwise.
- **Restore-side hardening (#817) guards backups the export-side fix can't retroactively cover** — a backup
  exported by pre-#817 code, or a zip corrupted by any other cause after export, can still have a manifest that
  references a `photos/...` path with no matching zip entry. `coverPhotoUri` and `CareLogEntity.photoUri`'s
  restore fallback is `zipPathToLocalPath[it]` (no `?: it`), so a zip path absent from that map resolves to
  `null` rather than writing the raw dangling zip-path string into the DB. `PlantPhotoEntity.uri` deliberately
  stays `zipPathToLocalPath[it] ?: it` — it must keep preserving a legitimate raw device URI when the backup was
  made with `includePhotos=false` (that value was never a zip path to begin with, so it's not ambiguous with a
  dangling reference).

## Schema version history (all new fields carry defaults for forward-compat)
| v | Added | Old backups deserialize to |
|---|---|---|
| v2 | liquid-fertilizer flag round-trip | — |
| v3 | `plantPhotos: List<BackupPlantPhoto>` | `emptyList()` |
| v4 | `BackupSettings.combineNotifications: Boolean` | `false` (#474) |
| v5 | `photoReminderEnabled: Boolean` | `false` (#480) |
| v6 | `themeMode: String` | `"SYSTEM"` (#139) |
| v7 | `fertilizingNotificationsEnabled: Boolean` | `true` (#223) |
| v8 | `BackupPlant.repottingIntervalDays: Int?` | `null` (#232) |
| v9 | `BackupRoot.customReminders: List<BackupCustomReminder>` + `BackupCareLog.customReminderId: Long?` | `emptyList()` / `null` (#232) |
| v10 | `BackupRoot.plantIssues: List<BackupPlantIssue>` | `emptyList()` (#564) |
| v11 | `BackupPlant.wateringConfidence: Int?` | `null` (#568) |
| v12 | `BackupPlant.wateringBaseIntervalDays: Double?` + `BackupPlant.pinIntervalToBase: Boolean` | `null` / `false` (#569) |
| v13 | `BackupRoot.wateringAdjustments: List<BackupWateringAdjustment>` + `BackupSettings.askBeforeChangingIntervals: Boolean` | `emptyList()` / `true` (#572) |
| v14 | `BackupPlant.wateringResetAt: Long?` + `BackupPlant.wateringFreezeUntil: Long?` | `null` / `null` (#571) |
| v15 | `BackupSettings.seasonalAmplitude: String` | `"STANDARD"` (#656) |
| v16 | `BackupSettings.postWateringReminderEnabled: Boolean` | `true` (#519) |
| v17 | `BackupPlant.dormancyStartMonth: Int?` + `BackupPlant.dormancyEndMonth: Int?` | `null` / `null` (#759, product ADR-0044) |
| v18 | `BackupPlant.fertilizingSeasons: String?` (comma-separated `FertilizingSeason` names; redefined in place from #286's original four nullable interval fields — never released) | `null` = every season (#795, product ADR-0049) |
| v19 | `BackupPlant.dormantWateringIntervalDays` | `null` (#785, product ADR-0046) |
| v20 | `BackupPlant.archivedAt: Long? = null` | `null` (unarchived) (#743) |

The device-local `post_watering_reminder_pending_at` modal token is transient operational state and is intentionally
excluded from `BackupSettings`; import clears it together with pending post-watering work and notification state.

`BackupSerializerTest` asserts `encodeDefaults = true` emits explicit null keys; `fullRoot()` sets every non-null
field so future nullable additions are caught by the round-trip test (#288). Instrumented `BackupManager` tests
cover round-trips ±photos, empty DB, future-schema warning, corrupt ZIP, missing backup.json, zip-slip, settings,
photo SHA-256, an archived plant's full history round-tripping and remaining archived (#743), an unreadable
photo URI restoring to `null` rather than a dangling zip path while incrementing `skippedPhotoCount` (#743), and
(#817) a hand-built zip whose manifest references a `photos/...` path with no matching zip entry restoring
`coverPhotoUri`/`CareLogEntity.photoUri` as `null` rather than the raw path.
