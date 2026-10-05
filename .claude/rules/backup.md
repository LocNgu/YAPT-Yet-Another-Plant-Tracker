---
description: .yapt backup/restore internals and backup schema version history
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/**/backup/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/**/BackupManager*.kt"
  - "app/src/androidTest/**/backup/**/*"
  - "app/src/test/**/backup/**/*"
  - "app/src/main/res/xml/*rules*.xml"
  - "app/src/main/AndroidManifest.xml"
  - "app/src/test/**/BackupRulesTest*.kt"
---

# Backup / Restore rules

`.yapt` ZIP export/import via SAF; optional photo inclusion and opt-in-at-export lossy photo optimization; settings
round-trip; forward-compat warning dialog. Optimization changes only the copies written into the archive, never a
gallery-owned source image.

## Mechanics (don't regress these)
- **Export builds the ZIP in a `cacheDir` temp file**, then streams it to the SAF destination; this prevents 0 KB exports to cloud providers (technical ADR-0014, #144).
- **Export includes archived plants** (`PlantDao.getAllPlantsIncludingArchived()`, #743) with their full history. `BackupPlant.archivedAt` round-trips verbatim, so archived stays archived.
- **Bulk queries:** fetch logs and reminders once (`getAllLogs()`/`getAllReminders()`) and group by `plantId` in memory; never N+1.
- **Photos are write-then-map in one pass (#817):** each candidate URI gets `openPhoto()` exactly once. Only after a successful zip copy is `photoMapping[uri] = zipPath` added; otherwise `skippedPhotoCount` increments.
  - There is no separate probe step: a gap between probe and write left dangling manifest paths.
  - `backup.json` is built from the final mapping and written **last**; import finds entries by name.
  - An exception during the copy still aborts the export.
  - `ExportSuccess.skippedPhotoCount` (default 0) selects `backup_export_success` or the `backup_export_success_with_skipped_photos` plural.
- **Restore streams photos to `cacheDir` temp files** (never into memory). They are tracked before copying, so `finally` always cleans up (#193/#195/#196).
- **Restore tolerates dangling photo paths** (old or corrupt backups): `coverPhotoUri` and `CareLogEntity.photoUri` use `zipPathToLocalPath[it]` with **no** `?: it`, so a missing entry becomes `null`. `PlantPhotoEntity.uri` keeps `?: it`, because it must preserve a raw device URI from an `includePhotos = false` backup.
- **`performImport` guards cleanup with `dbCommitted`:** written photo files are deleted only if the DB transaction did **not** commit (#175).
- A non-dismissable `BackupProgressDialog` blocks navigation during export/import (#365). The Settings tab also disables the bottom bar (`rules/navigation.md`).
- After a successful restore, `BackupManager.onImportCompleted` schedules the orphan photo sweep (`rules/photos.md`).

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
| v21 | `BackupPlant.repotPlanSeasonStartAt: Long?` + `BackupPlant.repotPlanMadeAt: Long?` + `BackupPlant.repottingSeasons: String?` (comma-separated `FertilizingSeason` names via `SeasonalFertilizing.encode`/`decode`) | `null` / `null` / `null` = no plan, every season (#809, product ADR-0057) |

The device-local `post_watering_reminder_pending_at` modal token is transient operational state and is intentionally
excluded from `BackupSettings`; import clears it together with pending post-watering work and notification state.

## Tests
- `BackupSerializerTest` asserts that `encodeDefaults = true` emits explicit null keys. `fullRoot()` sets every non-null field, so a new nullable field is caught by the round-trip test (#288).
- Instrumented `BackupManager` tests cover:
  - round-trips with and without photos, an empty DB, and settings
  - a future-schema warning, a corrupt ZIP, a missing `backup.json`, and zip-slip
  - photo SHA-256 checks
  - an archived plant's history (#743)
  - unreadable photos → `null` + `skippedPhotoCount`
  - a manifest path with no zip entry → `null` (#817)

## Backup-rule XML files (#824, product ADR-0053)
- **Two files:**
  - `data_extraction_rules.xml` (root `<data-extraction-rules>`, `android:dataExtractionRules`) governs API 31+. Cloud backup is off (nine-domain exclude-all, no `<include>`); device-transfer is left empty, so it carries everything, photos included.
  - `backup_rules.xml` (root `<full-backup-content>`, `android:fullBackupContent`) governs API 26–30 and stays unchanged.
- **The root tag must match the manifest attribute.** A mismatch makes `verifyTopLevelTag()` throw, which silently disables both cloud backup and device-transfer on API 31+ (the pre-#824 bug).
- `BackupRulesTest` (plain JVM) parses the manifest and both files.
