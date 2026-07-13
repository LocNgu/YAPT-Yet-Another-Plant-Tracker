# Product ADR-0019: Bottom navigation as top-level surface

**Status**: accepted

**Date**: 2026-07-12

## Context

Until now, YAPT has had a single top-level surface: `PlantListScreen`. Every other screen (`AddPlant`, `PlantDetail`, `AddCareLog`, `Settings`, `Graveyard`) is entered from the Plants list or its top bar. As the app has grown, all navigation is still funnelled through a `Scaffold` topBar with a gear icon (Settings) and an `ExtendedFloatingActionButton` (add plant).

Issue #414 introduces a second, equally long-lived surface — a Calendar view that visualises upcoming watering and fertilizing across the entire collection. Calendar is not a task-focused screen entered once to do one thing (like `AddCareLog` or `AddPlant`); it's a glanceable, planning-oriented view that the same user opens repeatedly per session, in parallel with the Plants list.

Several structural decisions needed to be made:

1. **How do we surface Calendar without hiding it inside a menu?** The Plants top bar is already crowded (sort menu, room filter, gear icon, FAB) and adding another entry point there devalues the feature.
2. **What qualifies as a top-level tab vs. a nested screen?** A clear rule prevents future features from thrashing the top-level shape.
3. **Where does Settings live?** With a bottom-nav, we could make Settings a third tab or keep it behind the gear icon.
4. **Where is the bottom nav visible?** On every screen (including deep flows like `AddCareLog`), or only on root screens?

## Decision

**Q1 — Introduce a Material 3 `NavigationBar` at the bottom of the app** with tabs for the current set of long-lived, glanceable, planning-oriented surfaces. As of this ADR that is two tabs: **Plants** (existing `PlantListScreen`) and **Calendar** (new `CalendarScreen`).

**Q2 — Rule for what qualifies as a top-level tab.** A screen is a top-level tab if and only if all of the following hold:
- It is opened many times per session by the same user (glanceable / planning surface).
- It presents a persistent, live view of the whole collection (or a whole-app concern).
- It has no "in-progress state" that would be lost by tab-switching mid-task.

Screens that fail any of these stay as nested destinations. Under this rule:
- **Tabs**: Plants, Calendar.
- **Nested**: AddPlant, EditPlant, PlantDetail, AddCareLog, Settings, Graveyard.

**Q3 — Settings stays behind the gear icon on the Plants top bar.** It is a low-frequency configuration surface (fails "opened many times per session"), so promoting it to a tab would violate the rule set in Q2 and give it real estate disproportionate to its actual use. This preserves the existing entry point unchanged.

**Q4 — Bottom nav visibility.** The `NavigationBar` is shown only when the current destination is a top-level tab (`PlantList` or `Calendar`). It is hidden on nested screens (AddPlant, EditPlant, PlantDetail, AddCareLog, Settings, Graveyard). Visibility is driven by the current back-stack entry's route in `YaptNavGraph` — no per-screen prop-drilling.

**Tab switching semantics.** Standard Material 3 behaviour: tapping the currently selected tab is a no-op; tapping the other tab navigates with `launchSingleTop = true` and `restoreState = true`, with a `popUpTo(startDestination) { saveState = true }` on the graph so each tab retains its scroll/filter state across switches. Only the Plants tab is the graph's start destination — deep links (notification tap → `PlantDetail`) still land on the Plants tab's back stack, unchanged from today.

## Consequences

- Calendar is discoverable at all times without cluttering the Plants top bar.
- The rule in Q2 gives future features a clear yes/no test for tab promotion — no ad-hoc "should this be a tab?" debates. Settings, Graveyard, and any per-plant screen fail the rule; a future "Rooms overview" or "Care timeline" surface could pass it.
- Existing product ADRs are unaffected: ADR-0004 (sort rules), ADR-0007 (skip override), ADR-0016 (quick-water sheet), ADR-0017 (quick-fertilize sheet), ADR-0018 (date-group dividers) all continue to describe Plants-tab behaviour, and the Calendar tab reuses their outputs (`PlantCareStatus.nextWateringDueAt`, `WaterFeedbackBottomSheet`, `QuickWaterSuggestion`, `groupPlantsByDueDate`) verbatim.
- Deep links continue to work: the notification-tap → `PlantDetail` flow lands on the Plants back stack, and back-navigation from `PlantDetail` returns to the Plants tab with the nav bar re-appearing.
- Adding a third tab in the future is a one-line change to the tab list; the rule in Q2 gates when that is appropriate.
