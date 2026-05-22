# ADR-0010: WorkManager REPLACE policy so reminder time changes take effect immediately

**Status**: accepted

**Date**: 2024-01-01

## Context

The daily care reminder is a `PeriodicWorkRequest` enqueued with a unique work name (`yapt_care_reminder`). When the user changes the reminder hour/minute in Settings, `ReminderScheduler.schedule()` is called again with the new time.

`WorkManager.enqueueUniquePeriodicWork` requires an `ExistingPeriodicWorkPolicy`:

- **`KEEP`**: if a work request with this name already exists, ignore the new one. The new time would not take effect until after the current period fires.
- **`UPDATE`**: updates the existing request's constraints and input data, but preserves the existing period timing. The initial delay for the new time is not re-applied.
- **`REPLACE`**: cancels the existing request and enqueues the new one from scratch, with the new initial delay calculated to fire at the user's chosen time.

Users expect that changing the reminder time in Settings takes effect the next day at the new time, not at some future cycle boundary.

## Decision

`ReminderScheduler.schedule()` uses `ExistingPeriodicWorkPolicy.REPLACE`. The initial delay is computed fresh each time: the next occurrence of `(hour, minute)` after the current moment, accounting for whether today's target time has already passed (if so, schedule for tomorrow).

See `ReminderScheduler.kt`.

## Consequences

- Changing the reminder time in Settings always takes effect immediately at the new time.
- `REPLACE` cancels and re-creates the work, which resets WorkManager's backoff state for that request. This is acceptable — reminder delivery timing is more important than backoff preservation.
- `BootReceiver` also calls `ReminderScheduler.schedule()` with stored preferences after device restart, using the same `REPLACE` policy, ensuring reminders resume at the correct configured time.
