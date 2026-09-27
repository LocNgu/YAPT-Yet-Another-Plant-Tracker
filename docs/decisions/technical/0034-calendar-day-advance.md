# Technical ADR-0034: Advancing a due date by N days uses a calendar-day advance, not a fixed 24h span

**Status**: accepted

**Date**: 2026-09-27

## Context

Every "advance a timestamp by N days" site in the scheduling path (`CareSchedule.computeWateringDue()`,
`computeWateringDue()`'s dormant-cadence branch, `computeFertilizingDue()`, `extendedCareDueAt()`,
`PlantDetailRescheduleActions.rescheduledRelativeDueAt()`, `SkipWateringReceiver.skipWatering()`) added
`TimeUnit.DAYS.toMillis(n)` — a fixed `n × 24h` span of real elapsed time.

Local calendar days are not always 24 hours. A DST fall-back day (e.g. `America/New_York`'s 2026-11-01
02:00 -> 01:00 transition) is 25 hours long. Adding a fixed 24h of real time on such a day can leave the
result on the **same** local calendar date:

| | |
|---|---|
| now | Nov 1, 00:30 local |
| `now + TimeUnit.DAYS.toMillis(1)` | Nov 1, **23:30 local** |
| intended | Nov 2 |

Every downstream consumer of these due dates compares calendar-day-wise via `Long.toLocalDate()`
(technical ADR-0013), so this silently produces a due date one calendar day earlier than intended —
once a year, in the roughly one-hour window right after a fall-back transition, per affected timezone.
Spring-forward is unaffected: a 23-hour day still advances the calendar date correctly.

Two things needed settling:

1. **Whether to snap to midnight while fixing this.** Several of these sites currently preserve
   time-of-day (a due date inherits the last watering's clock time). Snapping to midnight would be a
   second, unrelated behaviour change layered on top of the DST fix.
2. **Whether `CareSchedule` (pure-logic, heavily unit-tested) needs an injectable clock/zone.**
   `CareSchedule` already depends on the system default zone through the existing `Long.toLocalDate()`
   extension (technical ADR-0013), whose test precedent is `TimeZone.setDefault(...)` rather than an
   injected `ZoneId`.

## Decision

One shared helper, `internal fun Long.plusCalendarDays(days: Long, zone: ZoneId =
ZoneId.systemDefault()): Long`, added to `DateUtils.kt` next to `toLocalDate()`/`toStartOfDayMillis()`:

```kotlin
internal fun Long.plusCalendarDays(days: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(this).atZone(zone).plusDays(days).toInstant().toEpochMilli()
```

`ZonedDateTime.plusDays(n)` **preserves local wall-clock time-of-day** and always lands on local date +
n — no midnight-snapping. Outside a DST transition the result is identical to the old fixed-24h
arithmetic; the only behaviour change is the DST fix itself. Every site in the table above is routed
through it, so no two due-date/deferral sites can disagree on which arithmetic they use (the #586/#741
"the two paths cannot drift" guarantee this codebase already relies on elsewhere).

The zone parameter defaults to `ZoneId.systemDefault()` rather than being threaded through as an
injected dependency — `CareSchedule` already depends on the system default zone via `toLocalDate()`,
and the established test precedent (technical ADR-0013) is `TimeZone.setDefault(...)`, which this
helper's default honors identically. Tests pin `America/New_York` and a fixed fall-back instant via
`TimeZone.setDefault()` (or by passing an explicit `zone` argument), and must fail against the old
fixed-24h arithmetic.

**One site is deliberately left duration-based, not calendar-day-based:**
`WateringLifecycleReset.applyRepotReset()`'s `wateringFreezeUntil = resetAnchorMs +
TimeUnit.DAYS.toMillis(REPOT_FREEZE_WINDOW_DAYS)`. `isFrozen()` is a plain `now < freezeUntil`
elapsed-time check, not a calendar-date comparison — this is a genuine 4-week *duration*, not "the same
time of day, 4 weeks from now" read as a calendar date. Routing it through `plusCalendarDays()` would
be a no-op in almost every case (a fixed-length window whose comparison is itself elapsed-time-based
gains nothing from calendar-day-correct arithmetic) and would misdescribe the intent in code. This is
documented in place with a comment, not silently left as an inconsistency.

## Consequences

- Every due-date/deferral site in the scheduling path (watering, dormant cadence, fertilizing, extended
  care/custom reminders, +N reschedule, skip-watering deferral) now advances by the intended number of
  local calendar days, even across a DST fall-back transition.
- No behaviour change outside the roughly one-hour DST fall-back window per affected timezone per year —
  time-of-day preservation and forward-only semantics are unchanged everywhere else.
- The REPOT freeze window (extends technical ADR-0023's lifecycle-reset anchors) stays duration-based,
  by design, not by oversight — documented in place per this ADR.
- `daysSinceWatering`'s elapsed-duration division, `DemoDataTime` (dev-only seed data), and the
  watering-history chart's `DAY_IN_MS` axis math are out of scope — none of them advances a due date.
- Extends technical ADR-0013 (which governs day-*boundary normalization for comparisons*) rather than
  superseding it — that decision is about comparing two timestamps' calendar days; this one is about
  advancing a timestamp by a calendar-day count. Both rely on the same system-default-zone convention.
