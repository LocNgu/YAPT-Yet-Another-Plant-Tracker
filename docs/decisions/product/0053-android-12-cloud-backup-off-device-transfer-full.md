# Product ADR-0053: Android 12+ cloud backup off, device-transfer carries everything

**Status**: accepted

**Date**: 2026-09-27

## Context

#824 (split from #736). `AndroidManifest.xml` pointed both `android:dataExtractionRules` and
`android:fullBackupContent` at the same file, `backup_rules.xml`, whose root element is
`<full-backup-content>` — the API ≤ 30 legacy full-backup scheme. On API 31+, the platform requires
`dataExtractionRules` to resolve to a `<data-extraction-rules>` root instead. Per AOSP `FullBackup.java`
(identical on `android12-release`, `android13-release`, and `main`), the platform parses that file first
and calls `verifyTopLevelTag(parser, "data-extraction-rules")`, which throws on our mismatched root.
`BackupScheme.isFullBackupEnabled()`/`isFullRestoreEnabled()` catch that exception and return `false`, so
`BackupAgent.onFullBackup()` returns immediately and `onRestoreFile()` rejects every file — before any
fallback to `fullBackupContent` and before the device-to-device (D2D) compatibility branch. The practical
effect: **on Android 12+, YAPT's Google cloud backup and its phone-to-phone transfer both silently backed
up and restored nothing.** Android 8–11 devices use the legacy `fullBackupContent` scheme directly and were
unaffected — cloud backup and D2D both worked there, photos included.

Two other constraints shaped the fix, not just the bug:

- **The 25 MB cloud backup quota is all-or-nothing.** `BackupAgent.onQuotaExceeded()` drops the entire
  backup for the app, not just the photos pushing it over — a photo-heavy YAPT install already risked
  losing its settings/DB backup to its own photos before this issue existed (product ADR-0041's Context
  section documents the same quota against `filesDir/images`).
- **YAPT's product positioning is "no cloud, no accounts, no telemetry."** A byte-for-byte mirror of the
  existing exclusions into the new `<cloud-backup>` section would fix the throw, but it would also be the
  first time YAPT data is ever uploaded to a Google server from an Android 12+ phone — a bug fix that
  silently changes the app's privacy posture is not an acceptable trade for restoring cloud backup.

Options considered for the API 31+ `<cloud-backup>` section:

1. **Mirror the current exclusions verbatim.** Rejected — see above; it turns cloud backup on for the
   first time on the exact OS range where it never worked, and does nothing about the quota exposure.
2. **`android:allowBackup="false"`.** Rejected — this attribute is a single on/off switch that also
   disables Android 8–11's cloud backup and D2D, contradicting the decision (below) to leave 8–11
   untouched; the platform docs additionally describe its Android 12+ D2D behavior as OEM-dependent
   rather than a documented guaranteed-off, so it wouldn't even reliably achieve "cloud off, transfer on"
   on 31+ if we ever wanted that split.
3. **A sentinel `<include>` of a path that never exists**, relying on nothing being backed up because
   nothing matches. Rejected — it encodes the "back up nothing" intent as an accident of a magic path
   rather than as a readable, self-documenting rule.
4. **Chosen: an explicit nine-domain exclude-all**, detailed below.

## Decision

**Cloud backup is off, deliberately, on API 31+.** `app/src/main/res/xml/data_extraction_rules.xml`'s
`<cloud-backup>` section contains no `<include>` and exactly nine `<exclude path="."/>` elements, one for
each backup domain: `root`, `file`, `database`, `sharedpref`, `external`, `device_root`, `device_file`,
`device_database`, `device_sharedpref`. All nine are required, not just `root` — per AOSP
`BackupAgent.manifestExcludesContainFilePath()`, each domain is traversed from its own root and pruned by
**exact canonical-path match**, not by prefix, so excluding `root` alone would not also stop `file`,
`database`, or `sharedpref` from being backed up. Adding an `<include>` here would flip the whole section
into allow-list mode, the opposite of the intent, so none is present.

**Device-to-device transfer carries everything, photos included.** The same file's `<device-transfer>`
section is present but empty — no `<include>` or `<exclude>` at all. An empty section means "back up
everything except the platform's always-excluded cache/code_cache/no_backup directories," which already
covers the Room DB, DataStore settings, `filesDir/images`, and `filesDir/restored_photos` (the same two
photo directories technical ADR-0031's orphan-reclamation sweep manages). This is the actual bug fix:
phone-to-phone transfer on Android 12+ now moves data at all, where before it silently moved nothing.

The legacy no-op `<exclude domain="file" path="cache"/>` from `backup_rules.xml` is deliberately **not**
mirrored into the new file (documented in an XML comment there too) — it targets `filesDir/cache`, which
nothing in the app writes to, and the platform already auto-excludes `getCacheDir()`, `getCodeCacheDir()`,
and `getNoBackupFilesDir()` regardless of any rule file, so carrying it forward would just be a second
no-op.

`disableIfNoEncryptionCapabilities` is left unset on `<cloud-backup>` — moot, since that section now backs
up nothing at all on API 31+ for the attribute to condition.

**Android 8–11 (API 26–30) is unchanged.** `backup_rules.xml` stays byte-for-byte identical and remains the
target of `android:fullBackupContent`. Cloud backup and D2D transfer on those OS versions keep including
photos and remain exposed to the 25 MB cloud quota exactly as before this fix — accepted knowingly, since
changing that behavior is out of scope for a fix whose job is restoring API 31+ to a working, well-defined
state.

`android:allowBackup="true"` is unchanged for both OS ranges.

## Consequences

- **Phone-to-phone transfer on Android 12+ now actually works** — Room DB, DataStore settings, and both
  photo directories all move to the new device. Previously this silently transferred nothing.
- **A Google cloud restore onto an Android 12+ device now always starts YAPT empty**, even for a backup
  that was originally uploaded to Drive by an Android 8–11 phone under the unchanged legacy rules —
  restore eligibility is decided entirely by the *restoring* device's own rules, not the uploading
  device's. `.yapt` export/import is the only cross-device path back on Android 12+ after a
  Google-account-only device change; there is no cloud fallback.
- **Android 8–11 devices keep uploading a full backup, photos included, to Google's cloud backup**, and
  remain exposed to the 25 MB quota's all-or-nothing drop on `onQuotaExceeded()`. This is unchanged
  behavior, not a regression from this fix, and is accepted knowingly rather than addressed here.
- No change to `.yapt` export/import mechanics (`BackupManager`/`data/backup/*`), no Room schema or
  migration, and no Compose UI change — this ADR is scoped entirely to the two backup rule XML files and
  the manifest attribute pointing at them.
- A plain JVM test (`BackupRulesTest`) now parses the manifest and both referenced `res/xml` files and
  fails if the root-tag mismatch, the nine-domain exclude list, or `backup_rules.xml`'s content ever
  regress — this bug shipped once already with no automated coverage at all.
