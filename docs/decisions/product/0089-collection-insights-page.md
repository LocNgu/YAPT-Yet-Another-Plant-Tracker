# Product ADR-0089: Collection insights — a read-only Settings page that points at an action

**Status**: accepted

**Date**: 2026-11-28

## Context

Issue #1004 asks for a cross-plant summary. The owner's guard rail is that a number must help the user decide
something: vanity statistics (plant count, watering count, streaks) were split out to #1052. The Watering
overview (product ADR-0064) and the Repotting overview (product ADR-0059) already show that a read-only page
under Settings, next to those two, is the right home for an audit. Care stays task-only (product ADR-0054).

Alternatives considered:

- **A section on Care.** Rejected. Care is a task list; a summary of the past month is not a task.
- **A calendar month window.** Rejected. Numbers would reset to zero on the 1st, which reads as "nothing to see".
- **Detecting "repeatedly overdue" from the live queue.** Rejected. `TodayQueueAggregator` describes now, not
  history, and UI code must never derive due tasks. History is the only source that can answer "how often".
- **Counting every watering later than today's interval.** Rejected. A seasonal plant's interval moves, and a
  dormant plant is not late at all, so lateness is judged by the schedule in effect when the watering happened.

## Decision

- **A read-only page**, the Settings "Overviews" row after Watering overview (`Screen.CollectionInsights`, route
  `collection_insights`; bottom bar hidden, back returns to Settings). It never edits, logs, reminds or persists:
  no schema, DataStore or backup change.
- **A rolling 30-day window** of calendar days ending today, re-evaluated on a day change.
- **Three sections, each with a one-line "nothing to act on" message when empty** (no section is ever omitted):
  1. **Often watered late.** A plant with two or more late WATER logs in the window. A watering is late when its
     calendar day is after the previous WATER log's day plus the interval in effect on the later watering's day
     (the seasonal interval, or the fixed dormant cadence measured from the cycle start; a suspended dormant plant
     is never late). A gap that touches an away-mode trip (product ADR-0065, `AwayPeriods.spansAway`) is never
     late. Rows open Plant Detail on the Water tab so the user can widen the interval.
  2. **Care completed.** A count per care type in the window, in a fixed order, zero types omitted; the retired
     CHECK, MIST and NOTE types are never counted.
  3. **Active health issues.** Unresolved issues, longest first, with the days since they started; rows open the
     plant's Issues tab. Styled with `IssuePurple`, never a due-status colour (technical ADR-0020).
- Hidden and archived plants (`Plant.isVisible`) appear in no section. With no visible plant the page shows one
  empty state instead of the sections.
- No "currently overdue" marker is shown; if one is ever added it must come from `TodayCareRepository`.
- Meaning is carried by text and headings, never by colour alone.

## Consequences

- A user can spot plants whose interval is too tight and reopen long-running health problems from one place.
- Lateness is a heuristic over logs: a plant with a manually changed interval is judged by today's stored base,
  not by past manual edits. Accepted for a read-only hint.
- The page reads every care log once through `getAllLogs()`, as other whole-collection flows do.
- Game-like statistics stay out (#1052).
