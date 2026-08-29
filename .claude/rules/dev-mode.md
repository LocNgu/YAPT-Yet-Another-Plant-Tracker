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
- `FeatureFlagRegistry.ADAPTIVE_WATERING` (`adaptive_watering`, default off, #568) gates the multiplicative +
  confidence-weighted watering interval model — see `.claude/rules/schedule.md`. Unlike `PLANT_DETAIL_TABS`, the
  flag gates *behavior only* in `CareSchedule`/call sites; the backing `Plant.wateringConfidence` column and
  `.yapt` backup field ship unconditionally, so toggling the flag off/on never loses learned state (this is a
  deliberate exception to "flags need no schema" — the schema change here isn't gated by the flag, only its use is).
- `FeatureFlagRegistry.SEASONAL_WATERING` (`seasonal_watering`, default off, #569) gates the computed seasonal
  watering curve — see `.claude/rules/seasonal-watering.md`. Same posture as `ADAPTIVE_WATERING`: the backing
  `Plant.wateringBaseIntervalDays`/`pinIntervalToBase` columns and `.yapt` backup fields ship unconditionally.
  The amplitude picker itself lives on the main Settings screen (not the Developer section), visible only while
  this flag is on — only the flag's on/off `Switch` appears in the generic Developer-section flags list.
- `FeatureFlagRegistry.CHECK_REMINDERS` (`check_reminders`, default off, #570) reframes the watering reminder
  notification from "Water {plant}" to "Check {plant}" with Watered/Still-moist actions — see
  `.claude/rules/notifications.md`. Independent of `ADAPTIVE_WATERING` (different risk surface: this one touches
  `ReminderWorker`, the notification composer, and a new `StillMoistReceiver`); the Still-moist action's adaptive
  feed is gated on `ADAPTIVE_WATERING` separately, so the two flags compose rather than one implying the other.
  No new columns/backup fields — `CareType.CHECK` reuses the existing care-log pipeline entirely.

## Demo data (#523)
Two more Debug-actions rows: **Seed demo plants** / **Remove demo plants**, backed by `DemoData` (pure,
deterministic 8-plant dataset generator, `object` with a single `generate(now)` entry point — the anchor-time math
and per-plant definitions are split into `DemoDataTime`/`DemoPlantBuilders` to stay under Detekt's `TooManyFunctions`
threshold) and `DemoDataSeeder` (impure orchestration: writes/deletes via `PlantRepository`/`CareLogRepository`,
wrapped in one `database.withTransaction {}` so a killed process can't leave a partial demo set behind). Every demo
plant/log carries the `DemoData.NAME_PREFIX = "[Demo] "` name prefix; `seed()` is idempotent — it removes any
existing `[Demo] `-prefixed plants first, so repeated taps never stack duplicates — and `remove()` hard-deletes only
that prefix, cascading care logs, never touching a real plant. `SettingsViewModel.seedDemoPlants()` /
`removeDemoPlants()` lazily construct `DemoDataSeeder` (not a constructor param — avoids growing the VM's param list)
and route through the same `debugActionEvent` snackbar as the other Debug actions.

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
