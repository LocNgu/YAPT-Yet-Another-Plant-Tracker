---
description: Developer mode, feature-flag registry, and debug actions (Settings)
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/devmode/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/featureflag/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/settings/**/*"
---

# Developer mode / feature flags / debug actions (product ADR-0022, #514)

## Developer mode unlock (#520)
Tapping Settings → About version row 5× unlocks a **Developer** section at the bottom of Settings. Counter logic is
pure/Compose-free: `DeveloperModeUnlock.registerTap(currentTapCount, isDeveloperModeEnabled)` in `domain/devmode/`
→ `DeveloperModeTapResult` (new count + `Silent`/`Countdown(n)`/`Unlocked`/`Inert`). The counter is **screen-scoped
Compose `remember`** in `SettingsScreen`, not the ViewModel — resets when Settings leaves composition, no wall-clock
timeout; once unlocked every tap is `Inert`. AOSP-style countdown snackbars at taps 3/4/5.
`SettingsKeys.DEVELOPER_MODE_ENABLED` (default false); master `Switch` — off hides the section **and resets every
feature flag to its registry default**. Four read-only build-info rows (version+code, build type, Room DB version
from `PlantDatabase.DB_VERSION`, API level). Reachable in debug **and** release; excluded from backup (device-local,
no schema bump).

## Feature-flag registry (#521)
`data class FeatureFlag(key, titleRes, descriptionRes, default)` + `object FeatureFlagRegistry { val all }` in
`domain/featureflag/`. `FeatureFlags` (YaptApplication lazy singleton) wraps `settingsDataStore`, constructed with
`flags: List<FeatureFlag> = FeatureFlagRegistry.all` (**the injectable seam a test overrides**); exposes
`isEnabled(flag): Flow<Boolean>`, `setEnabled`, `resetAll()`. Each key = `"feature_flag_" + flag.key`
(`preferenceKeyFor`) — flags need **no schema/migration** to add or remove.
- `SettingsViewModel` takes `featureFlags: FeatureFlags = FeatureFlags(dataStore)` (keeps the constructor under the
  Detekt `LongParameterList` threshold — the flag list lives on `FeatureFlags`, not a second VM param); exposes
  `flags`, `featureFlagStates: StateFlow<Map<String,Boolean>>` (a `combine`, short-circuited to `emptyMap()` when no
  flags), `setFlagEnabled`.
- `SettingsScreen` renders one generic row per flag (`testTag("feature_flag_switch_${flag.key}")`); empty registry
  shows "No feature flags in this build". Adding a flag = registry entry + 2 string resources, no new Settings UI.

## Debug actions (#522)
Two non-destructive rows below the flags list; neither touches the DB or confirms.
- **Reset What's New seen state** — `resetWhatsNewSeenState()` removes `LAST_SEEN_VERSION_CODE` so the auto-show
  fires next launch (absent key reads as 0).
- **Run reminder check now** — `runReminderCheckNow()` checks POST_NOTIFICATIONS **itself** (before enqueueing, so
  the Snackbar is accurate) via the shared `NotificationPermission.isGranted(context)` helper (also used by
  `ReminderWorker.doWork()` so the two can't drift); only then calls `ReminderScheduler.runNow(context)` —
  `enqueueUniqueWork(RUN_NOW_WORK_NAME, REPLACE, …)` so rapid taps coalesce.
- Both emit via `SettingsViewModel.debugActionEvent: SharedFlow<String>`.

## Snackbar unification — do NOT re-add `dismiss()` (the instructive bug)
`SettingsScreen` routes **every** snackbar source (debug actions, backup export result, unlock countdown, dev-mode
disabled) into one screen-scoped `remember { MutableSharedFlow<String>(extraBufferCapacity=1, onBufferOverflow=
DROP_OLDEST) }`, collected by exactly one `LaunchedEffect` using **`collectLatest`**. There is deliberately **no**
`currentSnackbarData?.dismiss()` — `collectLatest` cancels the in-flight collector, and a cancelled suspended
`showSnackbar()` clears `currentSnackbarData` in its own `finally`. A plain `collect` parks inside `showSnackbar`
(which suspends until dismissed) and turns *replace* into *queue*; `DROP_OLDEST` does not fix that (it buffers
un-collected values, not the in-flight `showSnackbar`). Pinned by
`debugActionSnackbar_replacesTheUnlockSnackbar_ratherThanQueuingBehindIt`.
