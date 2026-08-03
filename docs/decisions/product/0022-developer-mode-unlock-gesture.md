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

*(Filled in by #521 — do not edit the sections above when adding this.)*

## Consequences

- The unlock gesture and master switch together are the **only** way to toggle developer mode; there is no ViewModel API that flips it without going through one of those two paths, so it can never be silently enabled by app logic.
- Because the tap counter lives in Compose `remember` state scoped to the Settings composable (not the ViewModel, which itself is scoped to the nav back-stack entry and would also reset on leaving/returning to Settings), the reset-on-leave behaviour is a natural consequence of ordinary Compose Navigation, not extra code to maintain.
- Shipping developer mode in release builds means the reviewer/QA and the human should treat it as a real, user-reachable surface (accessibility, string resources, Detekt) rather than a debug-only convenience — which is why AC28/AC29 (all strings in `strings.xml`, Detekt clean) apply to it like any other feature.
- This ADR intentionally leaves the flag registry, `FeatureFlags` singleton, backup exclusion, and the `restartRequired` deferral to #521, so it can extend this document additively rather than requiring a second ADR for a single feature area.
