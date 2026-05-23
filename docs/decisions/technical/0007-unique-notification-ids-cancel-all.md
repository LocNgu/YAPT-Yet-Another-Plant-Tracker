# ADR-0007: Unique notification IDs per plant with cancelAll before re-posting

**Status**: accepted (supersedes the original single-ID approach tracked in issue #7)

**Date**: 2024-01-01

## Context

The original implementation posted all reminders under a single hardcoded notification ID (`1001`). This meant only one notification was ever visible; posting a second one silently replaced the first. Users with multiple plants due for care only saw the last one processed.

After fixing this, each plant needs its own notification ID so all due plants can be visible simultaneously. A secondary concern: when a plant is deleted, its notification should disappear from the notification drawer automatically without requiring explicit cancellation logic in the delete path.

Alternatives considered:
- **Stable per-plant ID, cancel individually on delete**: requires the delete code path to also call `notificationManager.cancel(plant.id.toInt())`. Easy to miss, coupling between unrelated features.
- **`cancelAll()` before re-posting each run**: the worker runs once per day. Clearing all notifications and re-posting the current set is safe at that cadence and handles deletions, renames, and any other state change automatically.

## Decision

`ReminderWorker.doWork()` calls `notificationManager.cancelAll()` at the start of each run, before the plant loop. Each plant that is due or overdue is then posted with ID `plant.id.toInt()`. The notification title is the plant name; the body describes the specific care items due. Tapping the notification deep-links to that plant's detail screen via a `PendingIntent` carrying `plantId`.

See `ReminderWorker.kt`.

## Consequences

- All due plants are visible simultaneously in the notification drawer.
- Deleted plants never leave orphaned notifications — they simply aren't re-posted on the next daily run.
- There is a brief window at the start of `doWork()` where all notifications are cleared. Since the worker runs once per day in the background, this is imperceptible to users.
- `plant.id` is a Room auto-increment `Long`. Casting to `Int` is safe in practice (no app will have 2 billion plants), but is worth noting for future-proofing.
- The deep-link path (`plantId` in Intent extras) is read by `MainActivity.onCreate` and `onNewIntent`, and forwarded to `NavGraph` for navigation. The notification and navigation flows are coupled by this `plantId` contract.
