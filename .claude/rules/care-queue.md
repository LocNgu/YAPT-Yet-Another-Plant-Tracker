---
description: "Care tab (internal name Today) — queue aggregation, grid, sections, tile actions"
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/today/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/today/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/data/repository/TodayCareRepository*.kt"
  - "app/src/test/**/today/**/*"
  - "app/src/test/**/TodayCareRepository*.kt"
  - "app/src/androidTest/**/today/**/*"
---

# Care queue (product ADR-0054, product ADR-0056)

Canonical tasks come only from `TodayQueueAggregator`; the
screen is a `LazyVerticalGrid` of square plant tiles (`TodayCareGrid.kt`, 2–4 per row via the capped
`CareGridCells`) grouped by care type through `careTypeSections()` (Watering → Issue treatments →
Fertilizing → Custom reminders → Repotting → Photos, watering including the combined water-and-fertilize
task). Only Watering has Overdue / Today / Next 3 days sub-groups, taken straight from the aggregator's
`TodayTaskBucket` (no date math in the UI); every other section is flat in queue order. Headers read
`label · N` with N = **distinct plants** (`TodayCareTypeSection.plantCount`/`TodayWateringSubGroup.plantCount`)
and collapse on tap; the collapsed set is `rememberSaveable` in `TodayScreen`, so it survives tab switches and
rotation but resets on restart (a forgotten collapse must never hide next day's due care), and each header
exposes `stateDescription`/`heading()` for TalkBack. Tiles carry no due-date text and no overdue styling
(a red outline was tried and dropped, #842); the tile's content description always names the plant and
task. Tapping a tile opens Plant Detail on the tab matching the task kind via the optional `tab` route arg
(`TodayCareKind.plantDetailTab()`; combined water-and-fertilize → Fertilize; seeds only the initial tab,
#843). A long-press opens a `DropdownMenu` with only that kind's actions (`careMenuActions()` in
`CareMenuAction.kt`: Water/Reschedule, Water & fertilize/Reschedule, Fertilize, Repot…, Mark done, Take
photo), mirrored as semantics `customActions` plus an `onLongClickLabel`; each entry calls the same
`TodayViewModel` handler/prompt the old inline buttons did — never add inline buttons back or reimplement a
prompt. There is **no bulk completion or selection** on Care (the outer bottom bar never hides for Care;
`shouldShowBottomNavigation()` only takes Plant List's selection flag) and no plant-grouped layout or
developer flag: `QuickLogUseCase.completeTodayTasks()`/`BulkCompletionResult`, `plantSections()` and
`today_group_by_plant` are deleted (#842, product ADR-0056, amending product ADR-0054), while
`completeCustomReminder()` (single Mark done) and Plant List's `bulkLog()` stay. `TodayCareRepository`
injects the canonical `dayChangeTicker()` flow so its queue rolls over at the same local-day boundary as
Plant List and Calendar; Plant List, Calendar, and Care all save a reminder photo through the one
transactional `QuickLogUseCase.saveReminderPhoto()`. Because Care is the start destination, Plants may not
be on the back stack — never `getBackStackEntry(Screen.PlantList.route)` unguarded (#836, product
ADR-0054). Compose tests for a many-group Care screen use a viewport taller than the window (`tall = true`
in `TodayScreenTest`) so lazy items compose without scrolling, and then assert existence/order only.
