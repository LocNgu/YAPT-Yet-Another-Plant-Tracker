---
description: .yapt backup/restore internals and backup schema version history
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/**/backup/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/**/BackupManager*.kt"
  - "app/src/androidTest/**/backup/**/*"
  - "app/src/test/**/backup/**/*"
---

# Backup / Restore rules

`.yapt` ZIP export/import via SAF; optional photo inclusion; settings round-trip; forward-compat warning dialog.

## Mechanics (don't regress these)
- **Export** assembles the ZIP in a `cacheDir` temp file first, then streams to the SAF destination — prevents
  broken 0 KB exports to cloud providers (technical ADR-0014, #144).
- **Restore** streams photos to `cacheDir` temp files (never into memory) to avoid OOM; temp files are tracked in a
  map before copy so `finally` always cleans up (#193/#195/#196).
- Single bulk `getAllLogs()` query (not N+1); unreadable photo URIs silently skipped.
- `performImport` guards photo-file cleanup with a `dbCommitted` flag — written files are deleted only if the DB
  transaction has **not** committed, so a throw from `dataStore.edit`/`ReminderScheduler` after commit can't leave
  dangling URIs (#175).
- Navigation is blocked during export/import by a non-dismissable `BackupProgressDialog` (#365).

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

`BackupSerializerTest` asserts `encodeDefaults = true` emits explicit null keys; `fullRoot()` sets every non-null
field so future nullable additions are caught by the round-trip test (#288). Instrumented `BackupManager` tests: 9
cases (round-trips ±photos, empty DB, future-schema warning, corrupt ZIP, missing backup.json, zip-slip, settings,
photo SHA-256).
