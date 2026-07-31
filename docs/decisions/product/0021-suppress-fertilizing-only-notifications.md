# Product ADR-0021: Toggle to suppress fertilizing-only notifications

**Status**: accepted

**Date**: 2026-07-30

## Context

`ReminderWorker` fires a daily reminder for every plant where watering *or* fertilizing is due. For houseplant owners, fertilizing is far less time-critical than watering — a day or two late matters very little. A push notification sent *solely* because a fertilize is due adds noise without urgency, making users more likely to ignore all plant reminders (issue #223).

The open questions were: what happens to a plant that is due for *both* watering and fertilizing (does turning fertilizing notifications off strip the fertilizing line from its notification?), and how liquid-fertilizer plants — whose fertilizing is folded into their watering reminder (ADR-0008/ADR-0017) — should behave.

## Decision

Add a **Settings → Reminders** toggle, "Notify for fertilizing" / "Remind me when only fertilizing is due", persisted in DataStore (`fertilizing_notifications_enabled`, default `true`). The row is gated behind the existing notifications-enabled switch, sitting between the reminder-time and "Combine reminders" rows.

When the toggle is **off**:

- A plant whose *only* due care is fertilizing → **no notification**. The suppression is implemented in the pure `ReminderNotificationComposer.computeDueReminders(statuses, now, fertilizingNotificationsEnabled)`: a composed reminder is dropped when its `CareReminderItem` list contains no watering item (`WateringOverdue`/`WateringDueToday`).
- A plant that is **both** watering-due **and** fertilizing-due → notification sent **unchanged**, with the full body including the fertilizing line. The watering urgency makes the reminder timely regardless, so nothing is stripped.
- A plant that is **only** watering-due → no change.
- Liquid-fertilizer plants never surface a fertilizing-only reminder in the first place — their `FertilizeWithWatering` item is only added alongside a watering item — so the toggle has no additional effect on them.

The default (`true`) preserves today's behaviour exactly. The composer stays `Context`-free, so both branches are unit-tested without Robolectric (`ReminderNotificationComposerTest`); `ReminderWorkerTest` covers the end-to-end suppressed-vs-unchanged behaviour.

The setting round-trips through backup/restore like the other reminder settings (`BackupSettings.fertilizingNotificationsEnabled`, default `true`; backup schema bumped 6 → 7).

## Consequences

- Users who find fertilize-only pings noisy can silence them with one tap, without losing watering reminders or the fertilizing line on days when watering is also due.
- Turning the toggle off never removes information from a notification that would have been sent anyway — it only removes whole notifications that carried fertilizing as their sole reason. This "all-or-nothing per plant" rule is the non-obvious behavioural contract a future implementer must preserve.
- `ReminderWorker` reads one more boolean from DataStore per run, passed into the existing pure composer; no new persistent state beyond the one key.
- Old (pre-#223) backups deserialize `fertilizingNotificationsEnabled` to `true` via the field default, so importing an old backup does not silently suppress a user's fertilizing reminders.
