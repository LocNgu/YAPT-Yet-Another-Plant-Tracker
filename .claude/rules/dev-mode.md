---
description: Developer mode, feature-flag registry, and debug actions (Settings)
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/devmode/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/featureflag/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/settings/**/*"
---

# Developer mode / feature flags / debug actions (product ADR-0042, #514)

## Unlock (#520)
- **The tap counter:** tap Settings → About version 5× to unlock a **Developer** section. The pure counter logic is `DeveloperModeUnlock.registerTap(count, enabled)` → `DeveloperModeTapResult` (`Silent`/`Countdown(n)`/`Unlocked`/`Inert`). The count is a screen-scoped Compose `remember`, not ViewModel state: it resets when Settings leaves composition and has no timeout. Countdown snackbars show at taps 3/4/5.
- **The switch:** `SettingsKeys.DEVELOPER_MODE_ENABLED` (default false, device-local, not backed up). Turning it off hides the section **and resets every flag to its default**.
- **Build info rows:** version + code, build type, `PlantDatabase.DB_VERSION`, API level. The section works in debug and release.

## Feature-flag registry (#521)
- **Model:** `FeatureFlag(key, titleRes, descriptionRes, default)` + `FeatureFlagRegistry.all`. `FeatureFlags` (an app singleton) wraps the DataStore with `flags = FeatureFlagRegistry.all` as the test seam, and exposes `isEnabled(flag): Flow<Boolean>`, `setEnabled` and `resetAll()`.
- **Keys:** `"feature_flag_" + key`. Adding or removing a flag needs no schema change or migration.
- **ViewModel:** `SettingsViewModel(featureFlags = FeatureFlags(dataStore))` (the list lives on `FeatureFlags`, which keeps the VM under Detekt's parameter limit). It exposes `flags`, `featureFlagStates` (short-circuits to `emptyMap()` with no flags) and `setFlagEnabled`.
- **UI:** one generic row per flag (`testTag("feature_flag_switch_${key}")`); an empty registry shows "No feature flags in this build". A new flag = a registry entry + 2 strings, no new UI.
- **Lifecycle (product ADR-0042):** a graduating flag is deleted together with its losing branch, strings and tests.
  - `FeatureFlagRegistry.all` is **currently empty**, which is the expected steady state.
  - Graduated so far: `ADAPTIVE_WATERING` (#655), `SEASONAL_WATERING` (#656), `CHECK_REMINDERS` (#657), `PLANT_DETAIL_TABS` (#704), `TODAY_GROUP_BY_PLANT` (#842).
  - Orphan `feature_flag_*` DataStore booleans may linger on devices; nothing reads them (except the seasonal fixup's legacy check, `rules/seasonal-watering.md`).

## Demo data (#523)
- **Debug rows:** **Seed demo plants** / **Remove demo plants**. `SettingsViewModel` builds `DemoDataSeeder` lazily (not a constructor param).
- **Generation:** pure, deterministic `DemoData.generate(now)` (10 plants). Helpers are split into `DemoDataTime`/`DemoPlantBuilders` (Detekt).
- **Writes:** `DemoDataSeeder` runs in one `withTransaction {}`. Every demo row has the `DemoData.NAME_PREFIX = "[Demo] "` prefix. `seed()` removes existing demo plants first (idempotent); `remove()` hard-deletes only prefixed plants (logs cascade).
- **No MIST logs** are generated (#875).
- **Fixtures for manual testing (#571):**
  - ZZ Plant (confidence 3): log a REPOT to see the reset + freeze.
  - Rubber Plant (4): change its room to see a reset without a freeze.
  - Aloe Vera (2, null room): assigning a first room must *not* reset.
  - Monstera, Snake Plant, Fiddle Leaf Fig, Pothos, Peace Lily (null confidence, enough history) bootstrap on their next WATER log; Aloe, Cactus and Calathea keep their typed interval.

## Debug actions (#522, #519)
Non-destructive rows with no DB writes and no confirmation. All emit through `SettingsViewModel.debugActionEvent: SharedFlow<String>`.
- **Reset What's New seen state:** removes `LAST_SEEN_VERSION_CODE` (an absent key reads as 0).
- **Run reminder check now:** checks POST_NOTIFICATIONS itself first (the shared `NotificationPermission.isGranted()`, also used by `ReminderWorker`), then `ReminderScheduler.runNow()` (`enqueueUniqueWork(RUN_NOW_WORK_NAME, REPLACE)` coalesces taps).
- **Show drain-water reminder now:** writes the pending-modal token directly, bypassing the 30-minute delay and the permission.

## Snackbars — do NOT re-add `dismiss()`
- **One stream:** every Settings snackbar source goes into one screen-scoped `MutableSharedFlow<String>(extraBufferCapacity = 1, DROP_OLDEST)`, collected by one `LaunchedEffect` with **`collectLatest`**.
- **Why it works:** cancelling the in-flight `showSnackbar()` clears it in its own `finally`, so there is deliberately no `currentSnackbarData?.dismiss()`. A plain `collect` would queue instead of replace, and `DROP_OLDEST` doesn't fix that.
- Pinned by `debugActionSnackbar_replacesTheUnlockSnackbar_ratherThanQueuingBehindIt`.
