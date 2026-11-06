# Product ADR-0081: Same-day manual interval edits merge into one adjustment row

**Status**: accepted

**Date**: 2026-11-06

**Amends**: [ADR-0028](0028-why-this-date-watering-transparency-sheet.md) (the "a row on every evaluation" posture, for `MANUAL_EDIT` only).

## Context

Issue #799. Every manual watering-interval change wrote its own `MANUAL_EDIT` row, so a user fiddling with the
interval several times in one day pushed real model events out of the five-row "Recent adjustments" list.

## Decision

- When a `MANUAL_EDIT` is written and the plant's **newest** adjustment row is a `MANUAL_EDIT` from the same local
  day (`Long.toLocalDate()`, technical ADR-0013), that row's `afterIntervalDays` and `triggeredAt` are updated
  instead of inserting. `beforeIntervalDays` is kept, so the row reads "7 → 12" for the whole day.
- A net-zero day (7 → 10 → 7) keeps the row with `before == after`, shown as "unchanged".
- Any intervening row (WATER observation, `DIALOG_EDIT`, reset, ...) starts a new row, so history stays chronological.
- Only `MANUAL_EDIT` merges; every other trigger always inserts.
- One write path: `WateringAdjustmentRepository.recordManualEdit()` -> `WateringAdjustmentDao.recordManualEdit()`,
  a single Room transaction. Add/Edit Plant and Plant Detail both use it.
- Existing gates (`MANUAL_EDIT` only when the base-space value changes) are unchanged. No schema change; existing
  rows and restored backups are not retroactively merged.

## Consequences

- Learning state lives on `Plant`, not in these rows, so learning is unaffected.
- The audit trail no longer shows each intermediate manual value within a day.
