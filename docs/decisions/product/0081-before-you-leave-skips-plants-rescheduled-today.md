# Product ADR-0081: Before you leave skips plants rescheduled today

**Status**: accepted

**Date**: 2026-11-07

## Context

Issue #1019. Before you leave lists plants by their due date, so a plant the owner has just rescheduled that day can
still be listed (the new date lands before the first visit or inside the trip). #1018 already leaves out plants
watered today for the same reason: the owner has just dealt with them. Telling "rescheduled today" apart needs to know
when the override was made, which `wateringDueDateOverride` alone does not record.

## Decision

- `Plant.wateringDueDateOverrideMadeAt` (nullable epoch millis) records when the active override was made. It is
  metadata, not a learning write: it never touches the interval, confidence or `WateringAdjustment` rows, so a
  reschedule stays model-neutral (product ADR-0039). The override and its made-at are always written together in one
  statement and cleared together; an undo of a revert restores the captured made-at and never re-stamps now.
- `BeforeYouLeave.plan` leaves out a plant whose active override (the `maxOf()` winner over the schedule's due date)
  was made on the plan's day, whatever the new date. It is counted under "can wait", with no note in the sheet.
  `BeforeYouLeave.holds()` is unchanged. The Care queue, notifications and every other surface are unchanged.
- No backfill: existing overrides and old backups carry `null`, read as "made on an earlier day". The notification Skip
  action stamps made-at like any other reschedule.

## Consequences

- DB version 22 and backup schema v25 each gain one nullable column/field.
- A plant rescheduled today to a date that would otherwise matter for the trip is not offered; the owner chose it.
- The first Before you leave after the upgrade may still list a plant rescheduled earlier that day before updating.
