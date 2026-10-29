---
description: Root tabs, start destination, Settings tab, bottom-bar disabling during backup
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/ui/navigation/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/MainActivity.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/settings/SettingsScreen.kt"
  - "app/src/androidTest/**/navigation/**/*"
---

# Navigation and root tabs (product ADR-0054, product ADR-0058)

- **Root tabs:** Care · Plants · Calendar · Settings, with Care (internally "Today") as the start destination (product ADR-0054, amending product ADR-0019).
  - Settings was added by #855 (product ADR-0058) as a *named exception* to product ADR-0019 Q2, whose criteria still gate future tabs: Care has no route to Settings, and the bar would otherwise have only 2 items.
- **Settings is a real root tab:** no back arrow, and the same `navigateToRootTab()` save/restore block as the others. The old Plants top-bar gear and its `onNavigateToSettings` plumbing are gone. Graveyard, Hidden plants (`Screen.HiddenPlants`, #915, row shown only while a plant is hidden), Repotting overview and Watering overview (`Screen.WateringOverview`, #901, product ADR-0064; rows under the Settings "Overviews" section, Watering overview directly after Repotting overview; Graveyard stays under Backup & restore) stay nested, and Back returns to the Settings tab.
- **Away mode sitter screens (#899):** `Screen.SitterRhythm`, `Screen.BeforeYouLeave` and `Screen.PlanSitterVisit` are nested destinations (no bottom bar, back arrow pops once). They are reached through `AwayModeNavigation` (`navController.awayModeNavigation()` in `AwayNavigation.kt`) from the Settings Away mode section and the Care banner; `SettingsScreen` and `TodayScreen` take it as an optional parameter. Details: `rules/away-mode.md`.
- **Add Plant from Propagate (#960):** `Screen.AddPropagatedPlant` (`add_propagated_plant/{parentId}`) is a nested destination reached from Plant Detail's post-log offer and Family card; saving pops back to the parent's Plant Detail. Plain `Screen.AddPlant` (`add_plant`) is unchanged. Details: `rules/plant-detail.md`.
- **Back stack:** because Care is the start destination, Plants may not be on it. Never call `getBackStackEntry(Screen.PlantList.route)` unguarded (#836).
- **During backup/restore:** `SettingsScreen.onBackupInProgressChanged` (`LaunchedEffect` + reset on dispose, the `onSelectionModeChanged` precedent) feeds a flag in `YaptNavGraph`. That flag **disables — not hides —** every `YaptBottomNavigationBar` item while the operation runs, so the progress dialog can't be abandoned. The screen keeps its own `BackHandler` too.
- **Restore success** is `NavController.showRestoredPlantList()`: reset to Care, then open Plants with the message.
- **Bottom bar visibility:** `shouldShowBottomNavigation()` takes only Plant List's selection flag; Care never hides the bar.
