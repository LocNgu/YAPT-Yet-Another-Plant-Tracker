# Product ADR-0035: Debounced post-watering standing-water reminder

**Status**: superseded by [ADR-0036](0036-post-watering-foreground-presentation.md)

**Date**: 2026-09-08

## Context

After watering, excess water can collect in plant saucers and should be poured away. YAPT's daily
care reminder cannot help with this because it runs at a configured time rather than relative to a
care event. Issue #519 requests a reminder about 30 minutes after watering and raises a notification
storm problem: users often water several plants as one round.

The coalescing alternatives were one reminder per plant, one reminder anchored to the first plant,
a reminder debounced to the latest plant, and a capped first-plant/short-window hybrid. The issue
also left open whether to name plants, where a tap should land, whether the delay is configurable,
and how the feature interacts with notification settings and backup.

## Decision

Schedule one generic notification 30 minutes after the latest successfully recorded current-day
WATER log. Each additional watering replaces the pending unique WorkManager request, so a watering
round produces one reminder 30 minutes after the round finishes. Bulk watering schedules once after
its Room transaction commits rather than once per selected plant.

The fixed notification says to check for standing water and pour away excess water. It does not name
plants and has no action buttons. Tapping it opens Plants with a transient **Cared for today** sort;
the user's persisted sort preference is not overwritten.

The reminder is scheduled for every WATER insertion path: individual quick watering, bulk watering,
the full Add Care Log flow, and an automatically paired WATER log from liquid fertilizing. A rejected
same-day duplicate, an edit to an existing log, or a WATER log backdated to an earlier local calendar
day does not schedule or debounce the reminder.

Settings → Reminders includes a **Drain-water reminder** toggle, default on, shown only while master
notifications are enabled. The delay is fixed at 30 minutes. Turning either switch off cancels any
pending request; the worker also rechecks both settings and notification permission before posting.
The setting round-trips through backup schema v15 and defaults on for older backups.

The reminder uses the existing plant-care notification channel and reserved notification ID `-2`,
distinct from per-plant positive IDs and the combined daily reminder's `-1`. WorkManager persists the
one-time request through process death and reboot. Technical ADR-0025 covers coexistence with daily
notification cleanup. This decision complements ADR-0020's combined daily reminders; the two
coalescing policies remain independent.

## Consequences

- A watering round produces one alert instead of a notification per plant.
- Debouncing favors a single useful return point after the round, but the first plant in a long round
  can sit longer than 30 minutes before the reminder fires. This is the accepted trade-off.
- The fixed delay and generic copy keep the setting and notification lightweight, at the cost of no
  per-user timing choice and no plant-specific checklist.
- Using **Cared for today** includes any plant with care logged today, not only WATER logs. It reuses an
  existing useful destination without introducing persistent navigation/filter state.
