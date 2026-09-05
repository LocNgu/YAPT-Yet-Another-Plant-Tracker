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
- `ADAPTIVE_WATERING` graduated (#655) — the multiplicative + confidence-weighted watering interval model
  (`CareSchedule.computeAdaptiveInterval()`, see `.claude/rules/schedule.md`) now ships unconditionally; there is
  no registry entry or flag row for it anymore. `Plant.wateringConfidence` and the `.yapt` backup field, which
  already shipped unconditionally before the flag was removed, are unaffected.
- `FeatureFlagRegistry.SEASONAL_WATERING` (`seasonal_watering`, default off, #569) gates the computed seasonal
  watering curve — see `.claude/rules/seasonal-watering.md`. Same posture the graduated `ADAPTIVE_WATERING` flag
  had: the backing `Plant.wateringBaseIntervalDays`/`pinIntervalToBase` columns and `.yapt` backup fields ship
  unconditionally regardless of this flag's state. The amplitude picker itself lives on the main Settings screen
  (not the Developer section), visible only while this flag is on — only the flag's on/off `Switch` appears in
  the generic Developer-section flags list.
- `FeatureFlagRegistry.CHECK_REMINDERS` (`check_reminders`, default off, #570) reframes the watering reminder
  notification from "Water {plant}" to "Check {plant}" with Watered/Still-moist actions — see
  `.claude/rules/notifications.md`. This one touches `ReminderWorker`, the notification composer, and a new
  `StillMoistReceiver`; the Still-moist action always feeds the (now-unconditional) adaptive watering model.
  No new columns/backup fields — `CareType.CHECK` reuses the existing care-log pipeline entirely.

## Demo data (#523)
Two more Debug-actions rows: **Seed demo plants** / **Remove demo plants**, backed by `DemoData` (pure,
deterministic 10-plant dataset generator, `object` with a single `generate(now)` entry point — the anchor-time math
and per-plant definitions are split into `DemoDataTime`/`DemoPlantBuilders` to stay under Detekt's `TooManyFunctions`
threshold) and `DemoDataSeeder` (impure orchestration: writes/deletes via `PlantRepository`/`CareLogRepository`,
wrapped in one `database.withTransaction {}` so a killed process can't leave a partial demo set behind). Every demo
plant/log carries the `DemoData.NAME_PREFIX = "[Demo] "` name prefix; `seed()` is idempotent — it removes any
existing `[Demo] `-prefixed plants first, so repeated taps never stack duplicates — and `remove()` hard-deletes only
that prefix, cascading care logs, never touching a real plant. `SettingsViewModel.seedDemoPlants()` /
`removeDemoPlants()` lazily construct `DemoDataSeeder` (not a constructor param — avoids growing the VM's param list)
and route through the same `debugActionEvent` snackbar as the other Debug actions.

Two of the ten (ZZ Plant, Rubber Plant — #571) ship with a pre-adapted `wateringConfidence` (3 and 4) purely so a
developer can manually exercise the lifecycle-reset triggers without grinding out real watering history first: log
a REPOT on the ZZ Plant to see confidence drop to 0 and the 4-week freeze start, or edit the Rubber Plant to a
different room to see the reset with no freeze. Aloe Vera also carries a pre-adapted confidence (2) with its
already-`null` room, to demo the blank→filled exception (assigning its room for the first time must not reset it).
The rest of the dataset covers the cold-start bootstrap for free: every other plant keeps `wateringConfidence ==
null`, so the next WATER log against a plant with enough history (Monstera, Snake Plant, Fiddle Leaf Fig, Pothos,
Peace Lily) triggers `bootstrapBaseInterval()`, while the sparse-history plants (Aloe Vera, Cactus, Calathea)
correctly keep their typed interval.

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
