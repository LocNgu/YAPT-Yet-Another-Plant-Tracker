# Technical ADR-0043: Automatic local backups run as a unique periodic worker into a persisted SAF tree

**Status**: accepted

**Date**: 2026-11-05

## Context

Issue #1001 adds periodic `.yapt` exports to a folder the user picks. It must reuse the existing export
(technical ADR-0014: build in a `cacheDir` temp file, then stream to the destination), stay offline,
and not race `OrphanPhotoSweeper` (technical ADR-0031). The folder grant is bound to the device, and
the daily `ReminderWorker` clears every notification except reserved IDs (technical ADR-0026).

## Decision

- **Scheduling:** one unique `PeriodicWorkRequest` (`yapt_auto_backup`, `AutoBackupWorker`) at 1 or 7 days,
  with battery-not-low and storage-not-low constraints (no network constraint: the feature is offline; a run
  waiting on constraints is not a failure and never touches the counter), enqueued with `ExistingPeriodicWorkPolicy.UPDATE` so a frequency change re-times the
  same job. Disabling cancels it. WorkManager persists it across reboot and app update, so there is no
  app-start work.
- **Orchestration** lives in `AutoBackupRunner` (injected store, `BackupManagerInterface`, `BackupFolder`,
  fingerprint, clock), so the state machine is unit-tested without WorkManager. `BackupManager.exportBackup`
  is called unchanged and `ExportSuccess` is trusted: no read-back.
- **Folder access:** `OpenDocumentTree` plus `takePersistableUriPermission` (read and write). Files are
  listed, created and deleted through `DocumentsContract` (`SafBackupFolder`). A missing grant, a deleted
  folder or a `SecurityException` is `FOLDER_UNAVAILABLE`.
- **File names:** `yapt-backup-YYYY-MM-DD.yapt`. The export is written to a unique temp document
  (`yapt-backup-YYYY-MM-DD.yapt.<epochMillis>.partial`, which never matches the prune pattern), so a frozen
  or killed process can never leave a partial file under the real name. Only after `ExportSuccess` is an
  existing same-day file deleted (and the folder re-listed to confirm it is gone, because a provider would
  otherwise create `name (1).yapt`) and the temp renamed to the exact name (`DocumentsContract.renameDocument`,
  falling back to copy-then-delete where rename is unsupported). A failure before the old file is deleted
  deletes the temp and keeps the old file; a rename failure after it keeps the temp, the only complete copy.
  Stale temps are swept, by exact name, only after a successful run, never at run start.
- **Retention:** after a successful export only, list the folder and delete files whose names match the
  exact pattern beyond the newest N (3, 7 or 14, by the date in the name). Other files are never touched.
- **Skip unchanged:** a SHA-256 over the row lists and backed-up settings (plus the photo toggles) is stored
  on success; an equal fingerprint skips the run and keeps the last-success time. The fingerprint is
  computed before the export, so data changing during the export triggers another run, never a missed one.
- **State:** all device-local DataStore keys (`auto_backup_*`), excluded from `BackupSettings`: no backup
  schema bump, no Room migration. A restore leaves auto-backup off.
- **Failure state machine:** a failure increments a consecutive counter and records its kind; a success
  resets the counter, the kind and the notified flag. The notification fires when not already notified and
  the kind is `FOLDER_UNAVAILABLE` or the counter is at least 2.
- **Notification:** own channel `auto_backup` and reserved ID `-3`; `ReminderWorker` skips `-2` and `-3`.
  It honours the master notifications switch and `POST_NOTIFICATIONS`; the Settings status line reports every
  failure regardless. A success clears it.
- **Long-running worker:** a run that will actually export (not a fingerprint skip) calls `setForeground` with a
  silent, low-priority, ongoing "Backing up…" notification (channel `auto_backup_progress`, ID `-4`, foreground
  service type `dataSync`; manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and the merged
  `SystemForegroundService`). It is best effort: `setForeground` can throw from the background on API 31+, and
  that is swallowed and the export continues as a normal worker, never a failure. It is not gated by the master
  notifications switch (a foreground service needs one); a denied POST_NOTIFICATIONS merely hides it. `ReminderWorker`
  also skips `-4`.
- **Sweeper ordering:** no locking. A photo the sweeper deletes between the snapshot and the copy is skipped
  and counted in `skippedPhotoCount`, which the status line shows.

## Consequences

Backups are plain files the user can sync with their own tools. A same-day overwrite keeps the old copy until the new one is complete, at the cost
that peak folder usage roughly doubles during it (old and new coexist until the swap, about 760 MB for a
379 MB archive), so a device near its storage threshold can fail a run that used to succeed; the old copy
is kept. The
fingerprint reads every row on each run (cheap next to zipping photos, but not free). Restoring from the
folder uses the existing manual Import; no restore UI is added.
