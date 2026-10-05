---
description: Day-change ticker for date-dependent combine() flows, and how to test it
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/util/DayChangeTicker*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/**/*ViewModel*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/repository/TodayCareRepository*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/repotting/**/*"
  - "app/src/test/**/*ViewModel*Test.kt"
  - "app/src/test/**/DayChangeTicker*.kt"
  - "app/src/test/**/TodayCareRepository*.kt"
---

# Day-change triggers for date-dependent flows (#550, technical ADR-0035)

## Rule
No app flow emits just because time passes. So a `combine()` that reads or derives "today" needs a day-change trigger, otherwise it only re-evaluates on an unrelated change. This covers `DateUtils.todayRangeMillis()`, `computeStatus()`'s due math and `LocalDate.now()`.
- **The trigger** is `util/DayChangeTicker.kt`'s `dayChangeTicker(zone, nowProvider, maxPollIntervalMs)`: a cold `Flow<LocalDate>` that emits immediately, then once per local midnight. Each pass re-reads the clock and delays for at most the poll interval. Never replace it with a fixed 24 h ticker: local days are 23–25 h and long delays drift.
- **Wiring:** feed it into the `combine()` and ignore the value (`_`). At the 5-flow limit of the typed overloads, fold it into an existing flow first (`combine(existing, dayChangeTicker()) { v, _ -> v }`); don't switch to array `combine()`.
- **Injection:** constructor-inject it as `Flow<LocalDate>` defaulting to `dayChangeTicker()`. `SettingsViewModel` builds it internally (Detekt parameter limit).
- **Consumers:**
  - `PlantListViewModel.plantsWithStatusBeforeSearch` (the search stage downstream never re-reads it)
  - `CalendarViewModel.statusesForDay`
  - `TodayCareRepository.observeQueue()`
  - the Repotting overview pipeline

## Combine once, derive twice
Calendar builds `private data class StatusesForDay(day, statuses)` in **one** `combine(allPlants, logCount, seasonalAmplitude, today)`. Both `plantsWithStatus` and `plantsByDay` (`combine(statusesForDay, _visibleMonth)`) derive from it. If each stage read `today` separately, `plantsByDay` would briefly pair the new day with the old statuses. Apply the same rule to any two-stage chain that shares a trigger.

`CalendarViewModel.today` is public; `CalendarScreen` collects it for the `isToday` highlight and the "Today" sheet title. Never use `remember { LocalDate.now() }` on a screen that can stay composed across midnight.

## Testing
- **Never `advanceUntilIdle()` while the real ticker is collected.** Its endless `delay` loop hangs it, including inside Turbine's `.test {}`. Use bounded `advanceTimeBy(ms)` + `runCurrent()` (`DayChangeTickerTest`).
- **Every `PlantListViewModel`/`CalendarViewModel` test passes a non-real ticker**: a class-level `private val dayChangeFlow = flowOf(LocalDate.now())`. Tests that exercise rollover inject a controllable `MutableSharedFlow<LocalDate>`.
