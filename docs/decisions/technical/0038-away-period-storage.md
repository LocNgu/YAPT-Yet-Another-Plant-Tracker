# Technical ADR-0038: Away periods are calendar-day rows with stored visits, one shared predicate, and a worker-side gate

**Status**: accepted

**Date**: 2026-10-15

## Context

Away mode (product ADR-0065, #895) needs trips stored somewhere that several unrelated code paths can ask the same
question of: the adaptive observation, the reason-prompt gates, the history bootstrap, the daily reminder worker and
the sitter flow. Slice 1 (#896) lays that foundation with no behavioural change.

## Decision

- **Trips are local calendar days, not instants.** `away_periods` stores `firstDay` and `lastDay` as epoch days
  (`LocalDate.toEpochDay()`), inclusive. A trip is a set of local days, and every comparison already runs through
  `Long.toLocalDate()` (technical ADR-0013, ADR-0034), so a DST change or a time-zone switch cannot move a trip's
  edges. The table has no foreign keys: a trip is global.
- **Past trips are kept** (there is no UI for them). A backdated watering, a log edit or the cold-start bootstrap
  can still look at a gap that crosses an old trip. A DataStore "current trip" was rejected: the next trip would
  overwrite the one the first post-trip watering still has to be measured against.
- **Visits and their plants are stored, not derived.** `sitter_visits` (`awayPeriodId` -> `away_periods`, CASCADE;
  `state` stored as a String and read with `runCatching`, technical ADR-0011) and `sitter_visit_plants` (primary key
  `(visitId, plantId)`, `visitId` -> CASCADE, `plantId` -> `plants` CASCADE, nullable `watered`). A visit that
  didn't happen, or a skipped plant, leaves no WATER log to derive it from.
- **One migration and one backup schema carry everything.** DB 17 -> 18 (`MIGRATION_17_18`, plain `CREATE TABLE`s)
  and backup schema v22 (`BackupRoot.awayPeriods`, each trip carrying its visits and their plants, ISO-8601 dates)
  cover trips, visits and visit plants, so slices 2-5 add no migration. Restore skips a trip or visit with an
  unparseable date instead of failing, re-filters visits into their trip, and drops visit plants whose plant isn't
  in the backup.
- **One shared predicate.** `AwayPeriods.spansAway(periods, fromMillis, toMillis)` is true when any trip overlaps the
  inclusive local-day range between two instants, and false when `toMillis <= fromMillis`. The observation,
  feedback stripping, every reason-prompt gate and the bootstrap (whose dormancy filter becomes one gap-exclusion
  predicate rather than a second pair of parameters) all call it, so they cannot disagree.
- **Reminders are gated inside `ReminderWorker`, never by cancelling WorkManager work.** The worker checks whether
  today is an away day, clears the previous day's notifications and posts nothing. A stateless gate recovers by
  itself across reboot and backup restore; cancelling would need a second job to turn reminders back on.
- **No new due rules.** Every due date in the sitter flow comes from `TodayQueueAggregator`, and planned waterings
  are simulated as synthetic WATER logs.
- Only backup reads the new tables until slice 2. Away mode never writes a plant row.

## Consequences

- The trip table, repository and `AwayPeriods` logic can be built, tested and merged ahead of every consumer.
- Keeping past trips costs a few rows per trip and no UI; it is what makes post-trip learning correct.
- Because visits are stored, deleting a trip or a plant cascades cleanly, and an edit that strands visits outside the
  new range deletes them explicitly (the caller reports how many).
- Dropping the gate in favour of cancelling work later would need a replacement for the "turn it back on" job; this
  decision rules that out without a superseding one.
