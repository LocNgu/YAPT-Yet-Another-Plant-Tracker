# Technical ADR-0025: Selective daily-notification cleanup preserves independent alerts

**Status**: accepted

**Date**: 2026-09-08

## Context

Technical ADR-0007 made `ReminderWorker` call `NotificationManager.cancelAll()` before rebuilding
the daily per-plant reminder set. That safely removed reminders for deleted plants and stale
combined/per-plant mode notifications while daily care reminders were YAPT's only notification
surface.

Issue #519 adds an independent, event-relative notification 30 minutes after watering. An
unqualified `cancelAll()` would erase that standing-water alert whenever the daily worker happened
to run after it was posted. Giving the alert a unique ID prevents replacement collisions, but does
not protect it from `cancelAll()`.

## Decision

Supersede ADR-0007's blanket cleanup with selective cleanup. `ReminderWorker` enumerates YAPT's
active notifications and cancels every notification except the post-watering reminder's reserved
ID (`-2`) before rebuilding the daily set. Daily per-plant IDs remain `plant.id.toInt()`, and the
combined daily reminder remains `-1`.

This preserves ADR-0007's self-healing behavior for deleted plants and combine-mode changes while
allowing the independent alert to remain visible until the user acts on or dismisses it.

## Consequences

- A daily reminder run no longer erases an unhandled standing-water alert.
- Deleted-plant and stale combined/per-plant daily reminders are still removed on the next run.
- Future independent notification types must reserve their own IDs and be explicitly preserved by
  the cleanup policy, or replace this allowlist with a broader notification-category design.
- The selective scan relies on `NotificationManager.activeNotifications`, available below YAPT's
  minimum supported Android version.
