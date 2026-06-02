# Technical ADR-0013: Due-date comparisons use calendar-day granularity via `Long.toLocalDate()`

**Status**: accepted
**Date**: 2026-06-02

## Context

The original `CareSchedule.computeStatus()` computed overdue/due-soon status by comparing millisecond timestamps:

```kotlin
val isOverdue = nextDueAt != null && nextDueAt < now
val isDueSoon = nextDueAt != null && !isOverdue && (nextDueAt - now) <= ONE_DAY_MS
val daysBetween = (laterMs - earlierMs) / 86_400_000L
```

This caused two classes of bug:

1. **"Interval − 1" watering suggestion.** If a user waters at 8 PM on the exact due date, `actualIntervalDays` (computed the same way) rounds down and comes out one day short of the stored interval. With `JUST_RIGHT` feedback, the app suggested shortening the interval by one day — incorrect.

2. **"Overdue" shown before midnight.** A plant due at 11:59 PM would show "Overdue" for the last millisecond of the due day, and "Due today" would flip to "Overdue" immediately at midnight even though the calendar day had not changed for the user.

Millisecond arithmetic is semantically wrong for a plant care schedule: users think in calendar days ("I watered on Tuesday"), not 86,400-second periods.

## Decision

An `internal fun Long.toLocalDate(): LocalDate` extension is added to `DateUtils.kt`. It converts a Unix millisecond timestamp to a `LocalDate` using the system default timezone. This function is `internal` and shared via import by `CareSchedule` and `ReminderWorker`.

All due-date and interval comparisons now use calendar-day semantics:

- `isOverdue`: `nextDueDate.isBefore(today)`
- `isDueSoon`: `nextDueDate == today` (a plant is "due soon" only on its exact due calendar day)
- `formatCountdown`: `ChronoUnit.DAYS.between(today, nextDueDate)` — a negative result means overdue
- `daysBetween(earlier, later)`: `ChronoUnit.DAYS.between(earlier.toLocalDate(), later.toLocalDate())`
- `ReminderWorker.buildCareBody()`: same `ChronoUnit.DAYS` pattern

Unit tests that exercise date comparisons pin the JVM timezone to UTC in a `@Before` block (`TimeZone.setDefault(TimeZone.getTimeZone("UTC"))`) to prevent CI failures on machines with non-UTC system timezones.

## Consequences

- "Due today" stays accurate throughout the entire due calendar day, not just the first milliseconds.
- Watering on the exact due date with Just Right feedback produces zero suggestion delta (actual == current → no suggestion shown).
- The `toLocalDate()` extension is the single authoritative conversion point; no inline millisecond division anywhere in the codebase.
- Tests must pin the JVM timezone to produce deterministic results.
