# Product ADR-0054: Unified Today care queue with task-first default

**Status**: accepted

**Date**: 2026-09-27

## Context

YAPT exposes due care through Plant List cards, Calendar, and notifications, but none is a focused
place to work through everything that needs attention now. Issue #836 adds Phase 4 of the navigation
roadmap in #810: a Today root destination that turns the existing schedules into one actionable queue.

Two plausible workflows were considered. A task-first layout lets someone water every due plant,
then fertilize or repot; a plant-first layout lets someone finish every kind of care for one plant
before moving to the next. There is not yet usage data showing which should graduate as the permanent
layout. The product therefore needs one safe default and a temporary, device-local comparison path.

The queue also has to avoid inventing a second interpretation of due care. Watering, fertilizing,
dormancy, seasonal schedules, liquid fertilizer, custom reminders, issue treatments, and progress
photos already have established rules and actions elsewhere in the app.

## Decision

Today is the middle root tab in **Plants · Today · Calendar**. Plants remains the launch destination,
all three root destinations retain state while switching, and the bottom navigation stays hidden on
nested screens.

The default Today presentation is task-first. It shows canonical tasks in **Overdue**, **Today**, and
dated upcoming sections through the end of the third local calendar day. Tasks sort by due instant,
plant name, then stable task id. Archived plants and dates beyond that horizon are excluded.

The queue is a projection of the existing sources and rules, not its own scheduler:

- watering, fertilizing, and repotting come from `CareSchedule`, including dormancy and seasonal rules;
- liquid fertilizer becomes one water-and-fertilize task when both are due, never duplicate rows;
- every custom reminder is represented independently;
- an active issue is a treatment task only through its linked custom reminder; and
- progress photos appear only when photo reminders are enabled and use the established photo cadence.

Each row reuses the existing quick-care behavior, reason prompt, adaptive suggestion, reschedule
dialog, date picker, and camera path. Selecting several non-photo tasks enables one bounded bulk
completion. That completion uses one Room transaction, skips duplicate-day water/fertilizer writes,
logs repotting for today, preserves exact custom-reminder identity, and schedules at most one
post-watering reminder after commit.

`today_group_by_plant` is a temporary developer flag, off by default and excluded from backup. Turning
it on groups the same canonical tasks under each plant and places that plant under its most urgent
task's section; it does not create a second aggregation or action path. Turning developer mode off
resets the flag. The experiment must graduate by deleting the losing presentation and its flag.

Today reuses the canonical `dayChangeTicker()` introduced for Plant List and Calendar. The ticker
recomputes its delay to the next local midnight after every emission, so due-state and “cared for
today” boundaries roll over while the app stays open, including across daylight-saving transitions.

## Consequences

- Today becomes the single operational queue without changing any underlying schedule or backup data.
- Task-first is deliberately a default, not a claim about measured user preference; the temporary
  grouped layout provides the comparison path requested in the spec.
- Queue actions remain consistent with Plant List, Calendar, and Plant Detail because they share the
  same use case and domain schedule inputs.
- Bulk completion is all-or-nothing for database writes, but duplicate tasks are reported as skipped
  rather than aborting the batch.
- There is no Room schema or `.yapt` backup-schema change. The only new preference is a device-local
  developer flag.
- This amends ADR-0019's two-tab root set and visibility list; its qualification rule, start
  destination, Settings placement, and state-restoring tab semantics remain in force.
