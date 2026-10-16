---
description: Away mode — trip storage, date logic, backup and learning protection; the whole-feature design is product ADR-0065
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/away/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/away/**/*"
  - "app/src/androidTest/**/away/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/model/AwayPeriod*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/entity/AwayPeriodEntity.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/entity/SitterVisit*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/db/AwayPeriodDao.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/repository/AwayPeriodRepository.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/backup/RestoredAwayData.kt"
  - "app/src/test/**/away/**/*"
  - "app/src/test/**/*AwayPeriod*.kt"
  - "app/src/test/**/MigrationTest17To18.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/usecase/AdaptiveWateringObservation.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/usecase/WateringLifecycleReset.kt"
  - "app/src/test/**/*Away*Test.kt"
---

# Away mode (product ADR-0065, technical ADR-0038)

The design for the whole feature (trips, paused reminders, learning exclusion, Care banner, plant-sitter flow) is
product ADR-0065. This file records what exists today: **storage, date logic, backup and learning protection
(slice 2, #897), and the Settings section, reminder pause and Care banner (slice 3, #898)**. The sitter flow
(rhythm, Before you leave, visits, sharing, confirming) doesn't exist yet.

## Storage (DB 18)
- `away_periods(id, firstDay, lastDay, visitEveryDays?, createdAt)`: `firstDay`/`lastDay` are **inclusive local epoch days**, never instants. No foreign keys, because a trip is global. Past trips are kept.
- `sitter_visits(id, awayPeriodId -> away_periods CASCADE, day, state, createdAt)`, indexed on `awayPeriodId`. `state` is `PLANNED` / `CONFIRMED` / `DID_NOT_HAPPEN`, stored as a String and read with `runCatching` (unknown -> `PLANNED`).
- `sitter_visit_plants(visitId -> sitter_visits CASCADE, plantId -> plants CASCADE, watered?)`, primary key `(visitId, plantId)`, indexed on `plantId`. `watered` is `null` until the visit is confirmed.
- Deleting a trip deletes its visits and their plants; deleting a plant deletes only its visit-plant rows.
- `AwayPeriodDao` is an abstract class so `insertVisitWithPlants`/`replaceVisitPlants` can be `@Transaction`. `AwayPeriodRepository` (lazy singleton in `YaptApplication`) maps entities to `AwayPeriod`/`SitterVisit`/`AwayPeriodWithVisits`; visits come back ordered by day, visit plants by plant id.
- `MIGRATION_17_18` is plain `CREATE TABLE`s matching `18.json`.

## Pure date logic (`domain/away/AwayPeriods.kt`)
- `isAway(date, periods)`; `currentOrUpcoming(periods, today)` (the earliest trip whose last day is not before today).
- **`spansAway(periods, fromMillis, toMillis)` is the one predicate for every away exclusion**: any trip overlaps the inclusive local-day range of the two instants (via `Long.toLocalDate()`); `false` when `toMillis <= fromMillis`. A gap ending on a trip's first day, or starting on its last, spans it.
- `validate(firstDay, lastDay, today, existing, editingId)` returns a sealed `AwayPeriodValidation`, checked in this order: `FirstDayAfterLastDay`, `EndsInThePast` (last day before today), `TooLong` (over `MAX_TRIP_DAYS` = 90 inclusive days), `OverlapsTrip`, `AnotherCurrentOrUpcomingTrip`, else `Valid`. The start may be in the past while the trip is ongoing. `editingId` excludes the edited trip from the conflict checks, so it never conflicts with itself.
- `endEarly(period, today)` ("I'm back"): last day becomes yesterday; `null` (remove the trip) when that is before the first day; never extends a trip.
- `isVisitDayInside` / `visitsOutside(visits, firstDay, lastDay)`: a visit day must lie inside its trip. The caller deletes the visits an edit or `endEarly` strands and reports the count; WATER logs those visits already wrote stay.
- Tests pin the time zone (technical ADR-0013) and include a DST fall-back case.

## Trips UI, reminder pause and Care banner (slice 3, #898)
- **`AwayModeViewModel`** (`ui/screens/away/`, inner `Factory(application, awayPeriodRepository, settingsDataStore)`) serves Settings and the Care banner; `NavGraph` builds one per destination through the same factory. `uiState: StateFlow<AwayModeUiState?>` (`null` while loading) combines `observeAll()` with an injected `dayChange: Flow<LocalDate>` (default `dayChangeTicker()`, technical ADR-0035), so the banner appears and disappears at midnight. `todayProvider` (default `LocalDate.now()`) is what the mutations use; tests inject both, and a non-real ticker (never `advanceUntilIdle()` against the real one).
- **Mutations** return their `Job` (tests `join()` it) and run under one `Mutex`, re-reading the trips with their visits inside it: `saveTrip(first, last, editingId)`, `cancelTrip()` (only a trip that hasn't started), `imBack()` (only a trip that has). Each validates through `AwayPeriods.validate` (an invalid range emits `AwayModeEvent.Rejected`, writes nothing); an edit or I'm back uses `visitsOutside` and `AwayPeriodRepository.updatePeriodAndDeleteVisits` (one `@Transaction`, so the trip and its stranded visits change together) and reports the count as `AwayModeEvent.VisitsRemoved`. **Nothing here writes a plant row** (`AwayModeViewModelTest` pins it).
- **A change to whether *today* is an away day** (compared before/after inside the mutation) calls `onTodaysAwayStateChanged`, which the Factory wires to `ReminderScheduler.runNow()` **only while the in-app notifications switch is on**: `ReminderWorker` checks just the runtime permission, and the daily schedule is cancelled while reminders are off, so an unguarded run would notify against the user's choice. Planning, editing or cancelling an upcoming trip that never touches today does not run it.
- **`domain/away/AwayTripStatus.kt`:** `awayTripStatus(periods, today)` is `None` / `Upcoming(trip)` / `Away(trip)` over `currentOrUpcoming`; `showsBanner(today)` is true while away and from `AWAY_BANNER_LEAD_DAYS` (7, adjust in review) before a trip starts.
- **Settings section** (`AwayModeSettingsSection.kt`, not in `SettingsScreen.kt`): `SettingsScreen` takes an optional `awayModeViewModel` and renders the section between Reminders and Seasonal watering, always visible (away days matter for learning even with notifications off). A status row (`stateDescription` "No trip" / "Trip planned" / "Away"; subtitle "Oct 1 – Oct 21 · reminders resume Oct 22", or "Away now · …") and buttons: **Plan a trip** (none), **Edit dates** + **Cancel trip** (upcoming), **Edit dates** + **I'm back** (away). Cancel and I'm back are immediate with no undo (a trip writes nothing to plants).
- **Date sheet** (`AwayModeDateRangeSheet.kt`): a `ModalBottomSheet` (`skipPartiallyExpanded`) with a Material3 `DateRangePicker`, which returns **UTC-midnight-encoded** millis — convert only through `toDatePickerMillis()` / `datePickerMillisToLocalDate()`, never `toLocalDate()` (same trap as `CareDatePicker.kt`). It accepts any date so each failure can say why; the live `AwayPeriods.validate` result shows as a polite live-region message (`awayValidationText`: one distinct string per `AwayPeriodValidation`, conflict messages name the other trip's dates) and Save is disabled unless `Valid`. A start without an end is a one-day trip.
- **Care banner** (`AwayModeBanner.kt`): see `rules/care-queue.md`. Texts: away "Away until Oct 21 · reminders paused" with **I'm back**; upcoming "Reminders pause on Oct 18 · away until Oct 25" with no action. Slice 4 (#899) adds **Plan a sitter visit** and **Before you leave** here.
- **Date text:** `awayDayText(date, today)` uses `DateFormat.getBestDateTimePattern` ("MMMd", or "yMMMd" when the year differs from today's).
- **Reminders:** `ReminderWorker` gate in `rules/notifications.md`.
- Tests: `AwayTripStatusTest`, `AwayModeViewModelTest`, `AwayModeTextTest`, `ReminderWorkerTest` (the away cases); instrumented `AwayModeSettingsSectionTest`, `AwayModeBannerTest` (incl. the Care screen).

## Backup (schema v22)
- `BackupRoot.awayPeriods: List<BackupAwayPeriod> = emptyList()`; each trip carries `visits` and their `plants`, with ISO-8601 dates (`LocalDate.toString()`).
- Export reads the three tables once each (bulk) and groups in memory (`buildBackupAwayPeriods`).
- Import runs inside `performImport`'s transaction: `awayPeriodDao().deleteAll()` first, then inserts after the plants. `toRestoredAwayData(restoredPlantIds)` is pure and skips a trip or visit with an unparseable date, re-filters visits into their trip, drops visit plants whose plant is not in the backup, falls back to `PLANNED` for an unknown state, and collapses repeated ids so no insert can violate a key. Plant ids are restored verbatim, so there is no id remapping.

## Learning protection (slice 2, #897)
- **One predicate:** `AwayPeriods.spansAway` drives every exclusion below. Away mode adds no plant-row write and no new caller-snapshot write (#872).
- **Observation** (`AdaptiveWateringObservation`, optional `AwayPeriodRepository`): an away-spanning gap is observed `frozen` with the confidence transition suppressed and no feedback. Base unchanged, `null` confidence stays `null`, one `AWAY_EXCLUDED` row, never `DORMANCY_EXIT` (the exit decrement stays dormancy-only). Label precedence `DORMANCY_EXCLUDED` > `AWAY_EXCLUDED` > `FROZEN_POST_REPOT` > `WATER_NOT_ATTRIBUTED` > feedback triggers. When the history bootstrap wins, the triggering observation still gets its provenance row (dormancy's if both apply). Covers every log written inside a trip, backdated ones, and the first gap that *ends* after a trip; the first gap that *starts* after the last away day learns normally. Details: `rules/care-logging.md`.
- **Feedback strip:** `feedbackForLog()` clears feedback for an away-spanning gap, which also covers Add Care Log edits (`excludeLogId`).
- **Bootstrap:** `CareSchedule.bootstrapBaseInterval(…, isGapExcluded)` generalizes the dormancy filter into one predicate (OR-ed with the plant's dormancy months, which keep their own parameters for existing callers); `WateringLifecycleReset.BootstrapRequest.isGapExcluded` threads it, and the observation passes `spansAway` over the trips it already read.
- **`PlantCareStatus.isWateringGapAwaySpanning`** comes from `computeStatus(…, awayPeriods = emptyList())`; the derived `skipsWateringReasonPrompt` and `WateringTaskAction.skipsReasonPrompt` are the single reason-prompt gates for Care, Plant List and Calendar. Plant Detail's `isChosenDateAwaySpanning` checks the picked date against its own predecessor. **Drop the Calendar gate if #852 removes Calendar.**
- **Plumbing:** the trips flow goes in through `TodayCareRepository` (settings `combine()`, 4 inputs), `PlantListViewModel` (`statusInputs` folds it with the seasonal amplitude to stay at the 5-flow limit), `CalendarViewModel` (5th input) and `PlantDetailViewModel.awayPeriods()` (a suspend read next to `previousWateringBefore`), all via nullable constructor params so existing tests are unchanged; the ViewModel factories take the repository as required.
- **Label:** `WateringAdjustmentTrigger.AWAY_EXCLUDED` ("away — not counted", `adjustment_trigger_away_excluded`), appended last in the enum.
- Tests: `AdaptiveWateringObservationAwayTest`, `QuickLogUseCaseAwayTest`, `CareScheduleAwayTest`, `WateringLifecycleResetAwayTest`, `TodayQueueAggregatorAwayTest`, `TodayCareRepositoryAwayTest`, `PlantListViewModelAwayTest`, `CalendarViewModelAwayTest`, `PlantDetailScreenGateAwayTest`, `EnumResourcesAwayTest`.
