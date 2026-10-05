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

User-facing name **Care**; internal identifiers keep "Today".

## Aggregation (`TodayQueueAggregator`, pure)
- **The only source of Care tasks.** `TodayCareRepository.observeQueue()` combines the live repositories, settings and `dayChangeTicker()` and delegates to it. UI code never derives due tasks.
- **Inputs and horizon:** active plants, care logs, custom reminders, issues, photos, settings and an explicit `LocalDate`. It must reuse `CareSchedule.computeStatus()`, never reimplement due rules. The horizon is Overdue + Today + the next 3 local days, ordered by due instant, then lowercase plant name, then task id.
- **Liquid-fertilizer plants** get one combined water-and-fertilize task only when watering falls in the horizon and fertilizing is due no later than it. They never get a standalone fertilizer task.
- **Issues and photos:** an active issue relabels only its linked custom-reminder task. Photo tasks use the newest care-log or gallery photo and ignore the session-only reminder-popup suppression.
- **Repot plans** are bucketed by season state (season start while upcoming, Today while in season, Overdue on the season's last day once ended), never by the raw past start date.
- **Sections:** `careTypeSections()` (`domain/today/TodayCareTask.kt`) orders them Watering (incl. the combined task) → Issue treatments → Fertilizing → Custom reminders → Repotting → Photos, empty ones hidden, queue order kept inside each. Only Watering has `subGroups` (Overdue / Today / Next 3 days, mapped 1:1 from `TodayTaskBucket`, so the UI does no date math). Every section and sub-group carries a distinct-plant `plantCount`.

## Screen (`TodayCareGrid.kt`)
- **Layout:** a `LazyVerticalGrid` of square plant tiles, 2–4 per row (capped `CareGridCells`).
- **Headers:** `label · N` (N = distinct plants), collapse on tap, and expose `stateDescription`/`heading()`. The collapsed set is `rememberSaveable` in `TodayScreen`, so it survives tab switches and rotation but resets on restart: a forgotten collapse must never hide the next day's care.
- **Tiles:** no due-date text and no overdue styling (#842). The content description always names the plant and the task. A REPOT plan tile's second line is "Planned for spring".
- **Tap:** opens Plant Detail on the matching tab via the `tab` route arg (`TodayCareKind.plantDetailTab()`; combined → Fertilize; #843).
- **Long-press:** opens a `DropdownMenu` of that kind's actions only (`careMenuActions()` in `CareMenuAction.kt`), mirrored as semantics `customActions` + `onLongClickLabel`. Each entry calls the existing `TodayViewModel` handler/prompt. Never add inline buttons back or reimplement a prompt.
- **No bulk completion or selection on Care** (#842): the bottom bar never hides for Care, and there is no plant-grouped layout or flag. `completeCustomReminder()` (single Mark done) and Plant List's `bulkLog()` remain.
- **Reminder photos:** Care, Plant List and Calendar all save them through the one transactional `QuickLogUseCase.saveReminderPhoto()`.
- **Back stack:** Care is the start destination, so Plants may not be on the back stack. Never call `getBackStackEntry(Screen.PlantList.route)` unguarded (#836).
- **Tests:** many-group Compose tests use a viewport taller than the window (`tall = true` in `TodayScreenTest`) so lazy items compose without scrolling, then assert existence/order only.
