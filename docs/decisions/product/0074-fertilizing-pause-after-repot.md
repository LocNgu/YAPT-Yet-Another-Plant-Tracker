# Product ADR-0074: Fertilizing reminders pause for 30 days after a repot, derived from the repot log

**Status**: accepted

**Date**: 2026-10-29

## Context

Issue #965. Fresh potting mix is fertilized and freshly disturbed roots burn easily, so growers skip feeding for several
weeks after repotting. The app kept nagging on the old schedule. Alternatives considered: a user-configurable pause or
per-plant override (more settings for little gain), and storing a "fertilize-not-before" timestamp on the plant (needs a
Room migration and backup changes, and goes stale when a REPOT log is edited or deleted).

## Decision

- The pause is a fixed 30 calendar days (`CareSchedule.POST_REPOT_FERTILIZE_PAUSE_DAYS`, next to the new-plant grace of
  #428) after the newest REPOT log.
- It is derived from `lastRepottedAt`, which already feeds the repot due date: raw due =
  `max(lastFertilizedAt + interval | createdAt + 30d, repotDay + 30d)` via `plusCalendarDays`, then the season shift
  of product ADR-0049. It applies to fertilized and never-fertilized plants alike and still holds if the user fertilizes
  after the repot. Dormancy stays an independent pause. PROPAGATE has no effect (product ADR-0073). Watering is
  untouched.
- Technical ADR-0023's write-once rule covers state mutations (learning state, history rows). A fertilizing due date is a
  pure view over logs, like the repot due date, so deriving it is consistent: editing or deleting a REPOT log simply
  recomputes it, and there is no migration.
- Visibility is a Plant Detail Fertilize-tab note ("Paused after repotting until <date>") shown only while the pause is
  active and the reminder is on. Care already shows the later date. Every surface (Care, Plant Detail, ReminderWorker,
  Plant List, Calendar) passes `lastRepottedAt` into `computeStatus()`.

## Consequences

A backdated repot whose window has passed has no effect. The length is not configurable. A new surface that calls
`computeStatus()` must pass `lastRepottedAt` or it will disagree with the others.
