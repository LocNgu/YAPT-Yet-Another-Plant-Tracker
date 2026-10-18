---
description: Hidden plants — hiddenAt, what hiding excludes, the Hidden plants page, the Edit Plant row
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/hidden/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/addplant/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/data/db/PlantDao.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/repository/PlantRepository.kt"
  - "app/src/test/**/hidden/**/*"
---

# Hidden plants (#915, product ADR-0066)

A living plant the owner doesn't want to see right now. Separate from the Graveyard (`archivedAt`): no wording or behaviour of the Graveyard changes.

## State and queries
- `Plant.hiddenAt` / `PlantEntity.hiddenAt` (`Long?`, `null` = visible; DB v19, `Migration(18, 19)`). `Plant.isVisible` (`archivedAt == null && hiddenAt == null`) is the one domain check for "belongs on operational surfaces"; use it, not a bare `archivedAt == null`.
- `PlantDao.getAllPlants()` and `getAllRooms()` filter `hiddenAt IS NULL`, so every list, reminder and overview that reads them drops hidden plants. `getHiddenPlants()` / `getHiddenCount()` serve the Hidden plants page and Settings row (never include archived). `getAllNonArchivedPlants()` is for background passes that must still reach hidden plants (`YaptApplication.runSeasonalGraduationFixupIfNeeded()`).
- **Hide/Unhide are column-specific** (`PlantDao.updateHiddenAt`) and leave `updatedAt` alone, like archiving. They run from the Edit Plant ViewModel and rely on the column-specific UPDATE alone (the technical ADR-0036 point-3 style of write); `plantEditMutex` belongs to the Plant Detail ViewModel and is not taken. `archivePlant`/`archivePlants` also set `hiddenAt = NULL`, so a restored plant is visible.
- `AddEditPlantViewModel.saveEdit()` carries `hiddenAt = existing?.hiddenAt`: an unrelated edit must never unhide a plant. New `Plant` columns need the same treatment.
- The domain defence-in-depth filters (`TodayQueueAggregator`, the Watering/Repotting overview builders and snapshots, `BeforeYouLeave`, `VisitProjection`, `PlanVisitState`) use `isVisible`.

## Surfaces
- **Excluded everywhere operational:** Plants list, Care, reminders (`ReminderWorker`), Calendar, Watering/Repotting overviews, away-mode sitter lists including "Please don't water these". Present only on the Hidden plants page and in backups. An already-posted notification clears at the next daily `ReminderWorker` run.
- **Edit Plant** (edit mode only): a Hide plant / Unhide plant `ListItem` below the notes field, not in the top bar. It writes at once (not on Save). Hiding emits `Event.HiddenForUndo`; the screen's `onPlantHidden` calls `NavController.showHiddenPlantOnPlantList()` (same #836-safe return as archiving, via `showPlantResultOnPlantList()`), which hands `hiddenPlantId`/`hiddenPlantName` to the Plants entry's `savedStateHandle`. `PlantListViewModel.onPlantHidden()` then shows the "Plant hidden" Undo snackbar (`undoHide()` -> `unhidePlant()`). Unhiding stays on Edit Plant with a snackbar.
- **Hidden plants page** (`Screen.HiddenPlants`, nested under Settings like the Graveyard, no bottom bar): `HiddenPlantsViewModel` (inner `Factory`) lists `getHiddenPlants()` newest-hidden first; tap opens Plant Detail, **Unhide** per row. Care can still be logged from Plant Detail; learning is unchanged.
- **Settings row** ("Hidden plants", `SettingsViewModel.hiddenCount`) sits after the Graveyard row and shows only while `hiddenCount > 0`.
- **Plants empty state:** when no plant is visible but `PlantListViewModel.hiddenCount > 0`, the `else` branch of the empty-state `when` shows `no_plants_all_hidden` instead of `no_plants_yet`. Search, sort, room chips and multi-select are untouched.

## Backup
`BackupPlant.hiddenAt` (schema v23, `rules/backup.md`); older backups import visible.
