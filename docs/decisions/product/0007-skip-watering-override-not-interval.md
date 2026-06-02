# Product ADR-0007: "Skip watering" is a temporary due-date override, not an interval change

**Status**: accepted
**Date**: 2026-06-02

## Context

Users sometimes need to defer a watering without meaning to change how often they water in general. For example, they may be leaving for a weekend, or the plant is still visibly wet but the reminder has fired.

Two approaches were considered:

- **Extend the interval permanently**: simple, but conflates "I'm busy this week" with "my plant actually needs water less often". It would drift the interval away from the plant's real needs over time.
- **Push the next due date forward by N days (override)**: defers just this one occurrence without touching the stored interval. The follow-up interval suggestion dialog still appears separately, so the user can optionally make the skip permanent if they choose.

## Decision

Tapping "Skip watering" on the plant detail screen opens an `AlertDialog` with a +/− stepper (range 1–7 days, default 1). Confirming writes `wateringDueDateOverride: Long?` on the plant — the effective next due date becomes `max(computedDue, override)`. This column does not affect `wateringIntervalDays`.

Immediately after the skip dialog closes, the existing watering-interval `AlertDialog` fires (if a suggestion is pending), giving the user the option to extend the interval permanently. These are two separate decisions: "skip now" and "change interval".

The override is cleared automatically the next time a WATER care log is recorded, restoring normal interval-based scheduling. `SkipWateringReceiver` handles the "Skip watering" notification action by advancing the override by one additional day.

Room DB was bumped from v1 → v2 with `MIGRATION_1_2` (`ALTER TABLE plants ADD COLUMN wateringDueDateOverride INTEGER`). The field is included in backup/restore for round-trip fidelity.

## Consequences

- Users can defer a single watering without inadvertently changing their schedule.
- The interval suggestion dialog still fires after a skip, so users who want to make the change permanent can do so in one flow.
- The override is a one-time mechanism: it is not re-applied on subsequent due dates once the next WATER log clears it.
- An extra nullable column exists on every plant row, but it is null for the vast majority of plants at any given time.
