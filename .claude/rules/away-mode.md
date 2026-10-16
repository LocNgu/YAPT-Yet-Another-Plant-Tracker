---
description: Away mode — trip storage, date logic and backup; the whole-feature design is product ADR-0065
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/away/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/model/AwayPeriod*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/entity/AwayPeriodEntity.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/entity/SitterVisit*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/db/AwayPeriodDao.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/repository/AwayPeriodRepository.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/backup/RestoredAwayData.kt"
  - "app/src/test/**/away/**/*"
  - "app/src/test/**/*AwayPeriod*.kt"
  - "app/src/test/**/MigrationTest17To18.kt"
---

# Away mode (product ADR-0065, technical ADR-0038)

The design for the whole feature (trips, paused reminders, learning exclusion, Care banner, plant-sitter flow) is
product ADR-0065. This file records what exists today: **storage, date logic and backup only**. Only backup reads
the new tables, and there is no UI, string or reminder change.

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

## Backup (schema v22)
- `BackupRoot.awayPeriods: List<BackupAwayPeriod> = emptyList()`; each trip carries `visits` and their `plants`, with ISO-8601 dates (`LocalDate.toString()`).
- Export reads the three tables once each (bulk) and groups in memory (`buildBackupAwayPeriods`).
- Import runs inside `performImport`'s transaction: `awayPeriodDao().deleteAll()` first, then inserts after the plants. `toRestoredAwayData(restoredPlantIds)` is pure and skips a trip or visit with an unparseable date, re-filters visits into their trip, drops visit plants whose plant is not in the backup, falls back to `PLANNED` for an unknown state, and collapses repeated ids so no insert can violate a key. Plant ids are restored verbatim, so there is no id remapping.
