# Technical ADR-0019: Custom reminders as a child table, with a nullable un-enforced `CareLog` FK

**Status**: accepted

**Date**: 2026-08-17

## Context

Issue #232's remaining scope after repotting shipped (product ADR-0022) is a generic, free-text, recurring reminder
per plant — the delivery mechanism for disease/treatment schedules ("apply neem oil every 7 days") as well as
anything else a user wants to track. Unlike watering/fertilizing/repotting, a plant can have an **unbounded number**
of these, so the established "nullable `xIntervalDays` column on `plants`" pattern (ADR-0022) does not fit: there is
no single column to hold "the" custom reminder's interval or last-done date.

Two further questions had technical weight:

1. **Does completing a custom reminder just reset a `lastDoneAt`, or does it also write a `CareLog`?** The human
   confirmed (spec clarifications on #232) that it must write a `CareLog`, visible in the plant's journal — silently
   resetting a `lastDoneAt` with no visible history would be a worse experience than every other care type in the
   app, all of which log an event.
2. **How does a `CareLog` entry know *which* custom reminder it satisfied?** A plant can have several custom
   reminders, so a bare `CareType.CUSTOM` tag on the log entry is not enough to display "Neem oil treatment" instead
   of a generic label — the log needs a foreign key back to the specific reminder.

## Decision

**`CustomReminder` is a genuine child table** (`custom_reminders`, Room DB v7 → v8, `MIGRATION_7_8`), not a nullable
column: `id`, `plantId` (FK to `plants`, `ON DELETE CASCADE`), `name`, `intervalDays`, `lastDoneAt`, `createdAt`.
`CustomReminderDao`/`CustomReminderRepository` mirror `CareLogDao`/`CareLogRepository`'s shape (a list-returning
`Flow` per plant, plus a `*Once` suspend variant for one-shot reads in `ReminderWorker` and `BackupManager`) rather
than `PlantDao`/`PlantRepository`'s single-row shape, since the cardinality is "many per plant."

**`CareSchedule` gained a `List<CustomReminderStatus>` result, not more scalar fields.** `PlantCareStatus` already
has four scalar fields per extended-care type (`nextRepottingDueAt`/`isRepottingOverdue`/`isRepottingDueSoon`/
`lastRepottedAt`) — that shape only works for exactly one reminder per plant. `computeStatus()` gains a
`customReminders: List<CustomReminder> = emptyList()` parameter (defaulted, so every existing caller keeps
compiling) and computes one `CustomReminderStatus(reminder, nextDueAt, isOverdue, isDueSoon)` per reminder, reusing
the same `createdAt + interval` first-due anchor as repotting's private `extendedCareDueAt()` helper.
`ReminderNotificationComposer` gained `CustomReminderOverdue(name, days)` / `CustomReminderDueToday(name)`
`CareReminderItem`s, iterated from that list and joined into the notification body with the existing `" · "`
separator — no per-reminder icon or category, per the spec clarifications.

**Completing a reminder writes a `CareType.CUSTOM` `CareLog` carrying a new nullable `CareLog.customReminderId:
Long?`, with no FK constraint enforced at the SQLite level.** This is the one deliberate departure from the app's
usual FK discipline (every other relationship, including `custom_reminders.plantId` itself, has a `ForeignKey` with
an explicit `onDelete` policy). Two designs were considered for wiring a `CUSTOM` log back to its reminder:

- **A `ForeignKey` with `onDelete = SET_NULL`.** This is the "correct" relational answer, but `care_logs` is an
  existing table and SQLite's `ALTER TABLE` cannot add a foreign-key constraint to an existing table — only Room's
  full table-rebuild migration pattern (drop/recreate/copy, as `MIGRATION_3_4`→`MIGRATION_4_5` did for
  `plant_photos`) can add one. That is a much larger, riskier migration for a single nullable column.
- **A plain nullable `Long` column with no FK, added via a one-line `ALTER TABLE ADD COLUMN` (chosen).** The
  trade-off is explicit: a `customReminderId` can point to a row that no longer exists once its `CustomReminder` is
  deleted. This is treated as an expected, permanent state rather than an edge case — journal history is meant to
  survive the reminder that produced it (the same reason repotting/watering logs survive their own interval being
  turned off). Every place that resolves `customReminderId` to a name — `CareLogItem`'s title — does a lookup that
  tolerates a miss and falls back to the generic `CareType.CUSTOM` label (`ui/util/EnumResources.kt`) instead of
  crashing or throwing.

This establishes a reusable pattern: **a log entry that references a deletable, non-`plants` parent uses a plain
nullable id column with application-level fallback display, not a hard FK**, distinct from the CASCADE-FK pattern
used for every table that is scoped to a `plants` row for its own lifetime.

Backup schema bumped 8 → 9: `BackupRoot.customReminders: List<BackupCustomReminder>` (old backups → `emptyList()`)
and `BackupCareLog.customReminderId: Long?` (old backups → `null`), following the existing `BackupModels`/
`BackupSerializer` structure exactly.

## Consequences

- `CustomReminderDao`/`Repository` are a third near-duplicate of the `CareLog` CRUD shape (after `PlantPhotoDao`).
  Consistent with the rest of the codebase's "no generic repository/DAO abstraction" stance (manual DI, no Hilt —
  technical ADR-0001) at the cost of some repetition.
- A `CareType.CUSTOM` journal entry whose reminder was deleted permanently reads as "Custom care" (the generic
  label) instead of its original free-text name. This is an accepted, deliberate loss of specificity in exchange
  for not rebuilding `care_logs`' schema — the log entry itself (date, that *some* custom care happened) is
  preserved, only the reminder's name is not.
- `PlantDetailViewModel`'s constructor and `Factory` grow to 7 parameters (`@Suppress("LongParameterList")`,
  consistent with `CareSchedule.computeStatus()`'s existing use of the same suppression for a legitimately-growing
  parameter list) — manual DI means every new per-plant repository is a new constructor parameter; there is no
  container to inject it from.
- A future extended-care type with an unbounded per-plant cardinality (e.g. multiple photos-per-treatment) can reuse
  the same `List<XStatus>` shape on `PlantCareStatus` and the same nullable-non-FK log-linking pattern rather than
  re-deriving them.
