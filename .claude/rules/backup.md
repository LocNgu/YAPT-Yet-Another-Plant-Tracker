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
- **Photo `photoMapping` is probe-then-map, not populate-then-skip** (#743) — export attempts to open every
  candidate photo URI (the same `openPhoto()` logic `writePhotosToZip()` uses) *before* adding it to
  `photoMapping`, and only adds it on success, tracking a running skipped count. Every downstream field that
  resolves a photo path already does a nullable `photoMapping[uri]` lookup, so an unreadable photo now resolves
  to `null` in the manifest instead of the zip claiming a photo exists that was never written (which used to
  leave a dangling `photos/<uuid>_name.jpg` path in the restored DB, since none of the three restore call sites'
  `zipPathToLocalPath[it] ?: it` fallback distinguishes "not in this backup" from "failed to open"). `writePhotosToZip()`
  keeps its `continue`-on-failure as a defensive fallback for the rare case a file becomes unreadable between the
  probe and the actual copy (e.g. deleted mid-export) — no longer the primary mechanism.
  `BackupResult.ExportSuccess.skippedPhotoCount` (default `0`, so existing 2-arg construction still compiles)
  surfaces the count; `SettingsScreen` shows the existing `backup_export_success` string when it's `0`, and a new
  `backup_export_success_with_skipped_photos` plural (modeled on `bulk_snackbar_logged_with_skipped`) otherwise.

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
photo SHA-256, an archived plant's full history round-tripping and remaining archived (#743), and an unreadable
photo URI restoring to `null` rather than a dangling zip path while incrementing `skippedPhotoCount` (#743).
