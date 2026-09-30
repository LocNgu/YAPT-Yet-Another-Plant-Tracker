# Product ADR-0054: Unified Care queue with care-type sections

**Status**: accepted — bulk-completion, selection and bottom-bar-hiding clauses, the row presentation and long-press selection clauses, and the plant-grouping developer-flag clause amended by [ADR-0056](0056-care-grid-with-quick-action-menu.md); root tab set extended with Settings by [ADR-0058](0058-settings-root-tab.md)

**Date**: 2026-09-27

## Context

YAPT exposes due care through Plant List cards, Calendar, and notifications, but none is a focused
place to work through everything that needs attention now. Issue #836 adds Phase 4 of the navigation
roadmap in #810: a root destination that turns the existing schedules into one actionable queue. It
was first built as "Today"; the owner renamed it **Care** after trying it, because the queue spans
Overdue plus the next three days rather than only today. Internal identifiers (`Screen.Today`, the
`today` route, `TodayViewModel`, the `today_group_by_plant` flag key) keep the original name.

Two plausible workflows were considered. A task-first layout lets someone water every due plant,
then fertilize or repot; a plant-first layout lets someone finish every kind of care for one plant
before moving to the next. There is not yet usage data showing which should graduate as the permanent
layout. The product therefore needs one safe default and a temporary, device-local comparison path.
The first default grouped tasks by date bucket, but that repeated the same plant under several dates
and hid the natural batches (all the watering, then all the fertilizing); the owner replaced it with
sections by care type.

The queue also has to avoid inventing a second interpretation of due care. Watering, fertilizing,
dormancy, seasonal schedules, liquid fertilizer, custom reminders, issue treatments, and progress
photos already have established rules and actions elsewhere in the app.

## Decision

Care is the first root tab in **Care · Plants · Calendar** and the application's start destination.
All three root destinations retain state while switching, the bottom navigation stays hidden on
nested screens, and it also hides while Care or Plants is in selection mode. Notification and deep-link
entry points that target Plant List or Plant Detail keep working; back from a root tab pops to Care.

The default Care presentation is **sections by care type, watering first**: Watering (including the
combined water-and-fertilize task) → Issue treatments → Fertilizing → Custom reminders → Repotting →
Photos, with empty sections hidden. Inside a section rows keep the canonical order — due instant, plant
name, then stable task id — and each row shows its own relative due label, with overdue rows
highlighted. The queue still covers Overdue through the end of the third local calendar day; archived
plants and dates beyond that horizon are excluded.

Rows stay uncluttered: no task-type label (the action button already names the task, except custom
reminders and issue treatments, which keep their reminder or issue name because their button only says
"Done"), a larger plant photo, and no checkboxes until selection starts. Long-pressing a row enters
selection mode as on Plant List; while selecting, a tap toggles selection, selected rows are tinted with
a check indicator, and the task controls are hidden so taps cannot complete a task by accident. Action
buttons carry plant-and-task content descriptions for screen readers.

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

`today_group_by_plant` (titled "Group Care queue by plant") is a temporary developer flag, off by
default and excluded from backup. Turning it on groups the same canonical tasks under each plant and
places that plant under its most urgent task's date section; it does not create a second aggregation or
action path. Turning developer mode off resets the flag. The experiment must graduate by deleting the
losing presentation and its flag.

Care reuses the canonical `dayChangeTicker()` introduced for Plant List and Calendar. The ticker
recomputes its delay to the next local midnight after every emission, so due-state and “cared for
today” boundaries roll over while the app stays open, including across daylight-saving transitions.

## Consequences

- Care becomes the single operational queue without changing any underlying schedule or backup data.
- Type sections are deliberately a default, not a claim about measured user preference; the temporary
  grouped layout provides the comparison path requested in the spec.
- Making Care the start destination reverses the earlier "Plants remains the start destination"
  position. Plants is no longer guaranteed to sit at the bottom of the back stack, so flows that
  return to it (archiving from Edit Plant, restoring a backup) navigate to it explicitly instead of
  assuming it is already on the stack.
- Queue actions remain consistent with Plant List, Calendar, and Plant Detail because they share the
  same use case and domain schedule inputs.
- Bulk completion is all-or-nothing for database writes, but duplicate tasks are reported as skipped
  rather than aborting the batch.
- There is no Room schema or `.yapt` backup-schema change. The only new preference is a device-local
  developer flag.
- This amends ADR-0019's root destination set (now Care · Plants · Calendar) and its start
  destination (now Care); its qualification rule, Settings placement, and state-restoring tab
  semantics remain in force.
