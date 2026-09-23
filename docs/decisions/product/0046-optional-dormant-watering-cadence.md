# Product ADR-0046: Optional dormant watering cadence

**Status**: accepted — fixed option clause amended by ADR-0047

**Date**: 2026-09-23

## Context

Product ADR-0044 suspends watering throughout a configured dormancy window. That remains appropriate
for plants which must be kept completely dry, but other dormant plants still need sparse watering.
The ordinary watering interval is unsuitable for that job: it may be seasonally adjusted and learned
from feedback, while a dormant cadence is a deliberate, fixed safety schedule.

## Decision

Add nullable `Plant.dormantWateringIntervalDays`, with exactly three supported values: 28, 35, or 42
days. `null` preserves ADR-0044's full watering suspension. The value is active only while a complete,
valid month window contains today. Malformed or half-configured windows fail closed to the normal
schedule, and unsupported cadence values behave as `null`.

Watering exposes an explicit active mode: `NORMAL`, `DORMANT_CADENCE`, or `DORMANT_SUSPENDED`.
Fertilizing remains suspended throughout dormancy regardless of watering mode. In dormant-cadence
mode, the computed due date is the latest chronological WATER log plus the fixed cadence, floored at
the first day of the current dormant cycle. A never-watered plant is due today and remains due today
until its first WATER log. The ordinary seasonal/adaptive date becomes active immediately on leaving
the window. `wateringDueDateOverride` is applied only after the active computed schedule is selected.

The fixed cadence does not read or update seasonal/adaptive interval state. Waterings whose observed
gap touches dormancy keep ADR-0044's existing reason-prompt and adaptive-learning exclusions.

Room moves 15→16 with a nullable column, and `.yapt` backup schema moves 18→19 with a null default.
Add/Edit Plant and the Plant Detail Water tab offer the three values under the existing dormancy
controls. Disabling the window clears the cadence.

## Consequences

- Existing plants and backups retain full suspension because the new field defaults to `null`.
- Opted-in dormant plants participate normally in watering due groups, calendar dates, and reminders;
  they are not presented as no-care Dormant entries. Fertilizing remains paused.
- "Why this date?" identifies the dormant schedule and its fixed interval. Outside the window it
  explains the ordinary schedule.
- The small fixed option set favors understandable, safe schedules over arbitrary dormant intervals.
