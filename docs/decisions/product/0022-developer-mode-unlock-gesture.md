# ADR-0022: Developer mode unlock gesture, release-build availability, and defaults policy

**Status**: accepted

**Date**: 2026-08-03

## Context

#514 asked for a way to put experimental features on a real phone and toggle them at runtime, mirroring Android's own hidden "Developer options" unlock (tap the build number 7 times). The spec (issue #514, clarified in [the spec-clarifications comment](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/514#issuecomment-5170121794)) was split into four sub-tasks because it spans independently shippable layers:

1. **#520 (this PR)** — the unlock gesture, the persisted developer-mode state, the Developer section shell, and read-only build info.
2. **#521** — the feature flag registry, the `FeatureFlags` singleton, and the generic flag list UI.
3. **#522** — non-destructive debug actions (reset What's New seen state, run reminder check now).
4. **#523** — the demo-data seeder and remover.

This ADR is written with #520 and is extended by #521 (flag lifecycle, backup exclusion, reset-on-disable, and the `restartRequired` deferral) — see the "Extended by #521" section below, added in that PR.

No existing product ADR covered developer mode or feature flags, so this is a new decision, not a supersession.

Alternatives considered for the unlock gesture:
- **A visible menu item** ("Enable developer mode") — rejected: developer/debug surfaces are conventionally hidden behind an easter egg so they don't clutter Settings for ordinary users, and so a curious tap doesn't casually change app behaviour.
- **A long-press** on the version row — rejected in favour of AOSP's well-known 7(-ish)-tap pattern, which many Android users already recognize; YAPT uses 5 taps (not 7) since the feature surface is much smaller than platform Developer Options.
- **A wall-clock timeout on the tap sequence** (taps must land within N seconds) — rejected. It adds a `System.currentTimeMillis()`/clock dependency to a screen-scoped counter for no real anti-abuse benefit in a single-user offline app; naturally leaving the screen already resets the counter, which is enough.

## Decision

**Unlock gesture.** Tapping the Settings → About version row 5 times enables developer mode. AOSP-style countdown feedback: taps 1–2 are silent, tap 3 shows a snackbar "You are 2 taps away from developer mode", tap 4 "…1 tap away…", tap 5 "Developer mode enabled" and persists `SettingsKeys.DEVELOPER_MODE_ENABLED = true` to DataStore. The tap counter is **screen-scoped** (plain Compose `remember` state owned by `SettingsScreen`, not the ViewModel or DataStore) and resets to 0 whenever Settings leaves composition — there is no wall-clock timeout. Once developer mode is already enabled, every further tap is inert: no counter change, no snackbar, no state change. The version row's `clickable` `onClickLabel` is the neutral "Show app version" so screen readers never announce the easter egg.

Turning developer mode off is done via a master **Developer mode** `Switch` at the top of the Developer section (not the tap gesture) — this hides the whole section immediately, shows a "Developer mode disabled" snackbar, and requires the 5-tap gesture again to return. No confirmation dialog, matching the app's other instant-apply toggles (theme, reminders, keep-screen-on).

**Availability: both build types, identical behaviour.** Developer mode is reachable in **release** builds, not just debug — the entire point is to evaluate real, in-progress features on a real phone before committing to them, which requires it to exist in the build a tester actually installs. Debug and release builds behave **identically**: same unlock gesture, same section contents, same defaults. There is no debug-only affordance that release users don't get, and no release-only restriction that debug builds don't have.

**Defaults policy.** Developer mode itself defaults to **off** (`DEVELOPER_MODE_ENABLED` DataStore default `false`) in both build types. Per #521, every individual feature flag will also default **off** in both build types; a flag's default lives as a field on its `FeatureFlag` registry entry, not hardcoded per-build-type. There is no "flags default on in debug" carve-out — a developer opts in via the same 5-tap gesture as anyone else, keeping debug and release behaviourally identical end to end.

**Section placement.** The Developer section renders at the bottom of Settings, below About/What's New, separated by a `HorizontalDivider`, and only while developer mode is enabled. This mirrors every other section's `HorizontalDivider` + label header pattern already in `SettingsScreen.kt`.

**Room DB version as a single constant.** `PlantDatabase.DB_VERSION` is the one source of truth referenced both by the `@Database(version = ...)` annotation and the developer-mode build-info row, so the two can never drift apart.

## Extended by #521

#521 built the feature-flag mechanism on top of the shell #520 shipped: the `FeatureFlag` data class, an **empty** `FeatureFlagRegistry`, the `FeatureFlags` application singleton, and the registry-driven flag rows in the Developer section.

**Flag registry and mechanism.** `data class FeatureFlag(key, titleRes, descriptionRes, default)` is declared once, in a single `object FeatureFlagRegistry { val all: List<FeatureFlag> }`. The registry **ships empty** — the first real flag arrives with the first experimental feature (#508–#513); no sample/fake flag is shipped to prove the mechanism. `FeatureFlags` is a `YaptApplication` lazy singleton wrapping `settingsDataStore` (technical ADR-0001, ADR-0009), exposing an observable `isEnabled(flag): Flow<Boolean>`, `setEnabled(flag, enabled)`, and `resetAll()`. Each flag's DataStore key is derived from `flag.key` (`"feature_flag_" + key`) so adding or removing a flag never needs a schema/migration. `FeatureFlags` itself carries the flag list it manages (`flags: List<FeatureFlag> = FeatureFlagRegistry.all`) — this is the injectable seam that lets a Compose test supply a test-only registry entry (proving the generic rendering) without shipping that entry to users. The Settings screen renders one row per flag — title, description, `Switch` — generated **generically** from `viewModel.flags`; adding a flag requires only a registry entry and its two string resources, no new Settings UI code. With an empty registry the flag area shows an empty state ("No feature flags in this build"); the master switch and build-info rows still render regardless.

**Deliberate deviation from #521 AC10.** AC10 asked for the flag list to be its own injectable `SettingsViewModel` constructor parameter, separate from `FeatureFlags`. Doing that alongside AC14's `FeatureFlags` parameter would have pushed `SettingsViewModel`'s constructor to 7 parameters, tripping Detekt's `LongParameterList.constructorThreshold` (default 7, fires at `paramCount >= threshold`). The list instead lives on `FeatureFlags.flags`, and `SettingsViewModel.flags` delegates to it — one parameter, not two, satisfying AC14 while keeping the constructor under the threshold. This is recorded here as a considered trade-off, not an oversight.

**Defaults: every flag off, in both build types.** A flag's default lives on its own `FeatureFlag.default` field, not hardcoded per build type — but by policy every shipped flag's default is `false`. There is no "flags default on in debug" carve-out (consistent with the availability decision above: developer mode itself behaves identically in debug and release).

**Backup exclusion.** Neither developer mode nor any flag value is written to or read from `BackupSettings` — backup schema stays at v6, untouched by this feature area. Flags are device-local, transient experiment state; a mid-experiment backup restored to a fresh install must not silently enable a half-finished feature that install's build may not even support.

**Reset-on-disable.** Turning developer mode off calls `FeatureFlags.resetAll()`, returning every currently-registered flag to its registry default. This is wired into the master switch's off path (`SettingsViewModel.setDeveloperModeEnabled(false)`) — the seam #520 deliberately left there. Consequence: "developer mode off" always means a stock build with zero hidden state, never a build with a flag silently still on from a previous session. Re-enabling developer mode never restores prior flag values — the user opts back into each flag explicitly.

**No `restartRequired` marker — deliberately deferred, not overlooked.** With the observable-singleton design, "no restart" is the default behaviour of a Flow-backed value: `FeatureFlags.isEnabled()` recomposes any collector immediately on write, so AC15 ("a flag toggle takes effect without an app restart") is satisfied for free by the mechanism itself, not by extra code. A `restartRequired` escape hatch would cost an extra registry field, a string resource, a UI branch, and its own test surface — built for **zero** current flags, since the registry ships empty. It is purely additive to introduce later (one defaulted field on `FeatureFlag`, no migration) on the day a real flag actually needs it (e.g. one that gates a value read once at process start). Until then, every flag in the registry is assumed to be safely hot-swappable.

**Flag lifecycle: flags never accumulate.** When an experimental feature graduates (ships unconditionally) or is abandoned, its `FeatureFlag` entry **and both code paths** (the flag-on and flag-off branches) are deleted in the same PR that makes the decision. The registry is not a permanent settings surface or an audit log — a flag that outlives its experiment is technical debt. This is why the registry is expected to often be empty or near-empty in practice, not just at initial ship.

*(This section was filled in by #521; the sections above are #520's and were not edited.)*

## Consequences

- The unlock gesture and master switch together are the **only** way to toggle developer mode; there is no ViewModel API that flips it without going through one of those two paths, so it can never be silently enabled by app logic.
- Because the tap counter lives in Compose `remember` state scoped to the Settings composable (not the ViewModel, which itself is scoped to the nav back-stack entry and would also reset on leaving/returning to Settings), the reset-on-leave behaviour is a natural consequence of ordinary Compose Navigation, not extra code to maintain.
- Shipping developer mode in release builds means the reviewer/QA and the human should treat it as a real, user-reachable surface (accessibility, string resources, Detekt) rather than a debug-only convenience — which is why AC28/AC29 (all strings in `strings.xml`, Detekt clean) apply to it like any other feature.
- This ADR intentionally leaves the flag registry, `FeatureFlags` singleton, backup exclusion, and the `restartRequired` deferral to #521, so it can extend this document additively rather than requiring a second ADR for a single feature area.
