# Product ADR-0047: Configurable dormant watering cadence

**Status**: accepted

**Date**: 2026-09-23

## Context

Product ADR-0046 introduced sparse watering during dormancy with three fixed choices. Those choices
covered common four-, five-, and six-week schedules, but did not let a plant's caretaker choose the
cadence appropriate to its species, pot, or indoor conditions.

## Decision

Keep `Plant.dormantWateringIntervalDays` as the stored representation, but allow every whole-week
cadence from one through twelve weeks (7–84 days). The dormancy editor uses a discrete week slider,
defaulting to five weeks when dormant watering is enabled. `null` still means pause watering.

The twelve-week maximum keeps the dormant cadence bounded to roughly one season and avoids turning a
care reminder into an implicit multi-season pause. Non-week values and values outside the range are
invalid and behave as `null`. Existing 28-, 35-, and 42-day values remain valid as four, five, and
six weeks respectively.

All other product behavior in product ADR-0046 remains unchanged, including the active scheduling
modes, override ordering, learning exclusions, fertilizing suspension, backups, and migration.

## Consequences

- Caretakers can set a cadence from 1 to 12 weeks without a free-form value that is difficult to
  review or support.
- No Room or backup-schema migration is needed: the nullable persisted field remains days and every
  previously supported value is still valid.
- "Why this date?" and the editor express the cadence in weeks, while scheduling continues to use
  the exact stored day count.
