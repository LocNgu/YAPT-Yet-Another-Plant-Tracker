# Product ADR-0020: Combine-notifications toggle for daily care reminders

**Status**: accepted

**Date**: 2026-07-27

## Context

`ReminderWorker` posts one notification per overdue/due-soon plant, each with a per-plant "Skip watering" action (technical ADR-0007: unique per-plant notification IDs, `cancelAll()` before re-posting). A user with many plants can wake up to a wall of individual notifications. Issue #474 asked for a setting so users can opt into a single digest-style notification instead.

The open questions were: what the combined notification should contain, whether it should still be tappable to a specific plant, and what happens to the per-plant "Skip watering" action (a single notification can't carry N independent actions without an expandable, per-plant-actionable layout that this app does not build today).

## Decision

Add a **Settings → Reminders** toggle, "Combine reminders" / "Show all due plants in one notification", persisted in DataStore (`combine_notifications`, default `false`). The row is gated behind the existing notifications-enabled switch, matching the reminder-time row.

When enabled, `ReminderWorker`:

- Still calls `cancelAll()` at the top of `doWork()` in both modes, so switching modes between runs self-heals without stale notifications from the other mode.
- Posts a **single, count-only** notification instead of the per-plant loop: title is a plural string ("1 plant needs care" / "%d plants need care"), no per-plant body or BigText list.
- Deep-links to `MainActivity` with no `plantId` extra, landing on the Plants list (not a specific plant).
- **Omits** the per-plant "Skip watering" action entirely — it has no single-notification equivalent in this app's implementation. Users who want it stay on per-plant mode (the default).
- Posts under a dedicated fixed ID (`COMBINED_NOTIFICATION_ID = -1`) distinct from any `plant.id` (Room-generated IDs are always positive), so a stale combined notification and stale per-plant notifications never collide.
- Posts nothing when zero plants are due, matching per-plant mode's behaviour.

The notification-composition logic (which plants are due, and what care items each has) is factored into a pure, JVM-testable `ReminderNotificationComposer` in `domain/notification/`, decoupled from `Context` so both modes are unit-tested without Robolectric.

The setting round-trips through backup/restore like the other reminder settings (`BackupSettings.combineNotifications`, default `false`; backup schema bumped 3 → 4).

## Consequences

- Users who want fewer notifications get a real one-tap toggle; the default preserves today's behaviour exactly, so no existing user is surprised.
- Combined mode trades away the per-plant "Skip watering" action. This is an accepted, documented tradeoff, not an oversight — a future iteration could explore an expandable/grouped notification with per-plant actions, but that's out of scope here (also out of scope: Android notification-channel *groups*, which are a different mechanism from a single digest notification).
- `ReminderWorker` now reads the toggle from DataStore on every run, alongside its existing reminder-time reads elsewhere in the app — no new persistent state beyond the one boolean key.
- Old (pre-#474) backups deserialize `combineNotifications` to `false` via the field default, so importing an old backup does not silently opt a user into combined mode.
