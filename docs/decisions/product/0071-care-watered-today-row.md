# Product ADR-0071: Care lists the plants watered today in a compact "Watered today" row

**Status**: accepted

**Date**: 2026-10-24

## Context

Since #931 (product ADR-0069) Care shows Watering · Overdue / Today / Tomorrow. Once nothing is overdue and everything
due today is done, the grid starts straight at **Watering · Tomorrow**, with no gap or message above it. Nothing on
Care confirms that today's watering is finished, and the user cannot see which plants they just watered without
opening Plant Detail (#939).

Alternatives considered:

- **Full-size tiles for the watered plants** (the same square tiles as the task sections). Rejected. A day with many
  waterings pushes Watering · Tomorrow off screen, which is the content the user opens Care to see next.
- **A "done today" list for the other care types** (fertilizing, repotting, photos, reminders). Rejected as out of
  scope: watering is the routine daily action and the only kind with a Tomorrow window; the other kinds are rare
  and already confirmed by their tile disappearing.
- **An adaptive photo count** that fills the available width. Rejected. A fixed five photos plus a "+N" circle keeps
  the row one predictable height, and a 320dp phone still fits six 48dp slots.
- **Hiding a plant from Watering · Tomorrow because it is already in Watered today.** Rejected. A plant watered today
  with a one-day interval is genuinely due tomorrow; each section answers its own question, so there is no
  cross-section dedup.
- **Deriving the list in the UI, or with a new Room query.** Rejected. `TodayQueueAggregator` already receives every
  care log and the injected `today`, so the list is one linear pass there and the UI does no date math.

## Decision

**A new Care section, "Watered today · N".** It lists each visible plant (`Plant.isVisible`) that has a WATER care log
whose `loggedAt` falls on the aggregator's `today` (a local calendar day). A combined water-and-fertilize action counts
through its paired WATER log; FERTILIZE, REPOT, PHOTO, CUSTOM and the retired CHECK / MIST / NOTE logs are ignored.
N is the number of distinct plants, and a plant with several WATER logs today appears once with its newest
`loggedAt`. A log counts by its stored `loggedAt` only: a past-day log entered today does not count, and a log picked
for today counts however late it was entered.

**Data.** `TodayQueueSnapshot.wateredToday: List<TodayWateredPlant(plant, wateredAt)>` is built by
`TodayQueueAggregator.build()` from the logs it already groups by plant, ordered newest watering first, then lowercase
plant name, then plant id. It is independent of `horizonEnd` and `nonWateringHorizonEnd`, and the sitter flow and
every other caller keep reading only `tasks`. `TodayQueueSnapshot.hasTasksDueNow` is true when any task is not
`Upcoming`. `TodayCareRepository` is unchanged: `dayChangeTicker()` already feeds its `combine()`, so the list empties
at midnight with no new ticker and no extra Room query.

**Content.** A compact row, not tiles: round plant photos (the cover photo or the `PlantPhoto` placeholder) with a
decorative check badge and the plant name underneath. Five photos show, then a "+N" circle (N = count − 5) that wraps
the row onto extra lines to show every plant, ending in a "Show less" circle. Each photo is one button named
"<plant>, watered today" with a hit target of at least 48dp; the slot size is `min(72dp, width / 6)` and the row is
left-aligned. Tapping a photo opens Plant Detail on the Water tab. There is no long-press, quick-action menu or undo:
a watered entry is not a task, and "Water" would hit the same-day duplicate guard. The check badge uses the primary
colour, never the due-status colours or `IssuePurple`.

**Position and states.** The order is Watering · Overdue → Watering · Today → **Watered today** → Watering · Tomorrow →
Issue treatments → Fertilizing → Custom reminders → Repotting → Photos. It shows whenever at least one plant was
watered today, including while tasks remain, and is the first section when Overdue and Today are empty. Care has four
states: first use (`activePlantCount == 0`); caught up (no tasks, nothing watered), unchanged; no tasks but at least one
plant watered, where the Watered today header and row sit above the existing "You're all caught up" message; and the
grid otherwise. When nothing was watered and nothing is due today but tasks exist (only Tomorrow), a non-interactive
"Nothing due today" line with a decorative check sits at the top, above Watering · Tomorrow.

**Header and state.** The header behaves exactly like the other section headers: sticky (`stickyHeader`), collapses on
tap, reads `Watered today · N`, and exposes `heading()`, Expanded/Collapsed `stateDescription` and an Expand/Collapse
click label. Its collapse key is `section:WATERED_TODAY` in the same per-session `collapsedGroups` set. The "+N"
expansion is a separate `rememberSaveable` Boolean in `TodayScreen`: it survives tab switches and rotation, resets on
restart, is not reset at midnight, and is inert when the count is five or fewer.

**Amends.** Product ADR-0069's section list and caught-up state gain the Watered today section and the caught-up
variant above, and product ADR-0056's "every section is a grid of square tiles" clause no longer holds for this one
section. Product ADR-0054's single queue remains the only source of tasks; this list is not a task.

## Consequences

- Care confirms finished watering at a glance, and the Tomorrow section stays on screen: the row is one fixed-height
  strip for up to five plants and grows only when the user asks.
- No Room schema, migration or `.yapt` backup change. The Away/sitter flow is unaffected because it reads only `tasks`.
- A plant can appear in Watered today and in Watering · Tomorrow at once (a short interval); this is intended.
- The empty-state gate on Care now checks `wateredToday` as well as `tasks`, so a UI test that builds a
  `TodayQueueSnapshot` by hand gets an empty `wateredToday` by default and keeps the old behaviour.
- A new Care state is introduced; anything adding another Care section must place it relative to the watering
  sections through `wateredTodayInsertIndex` rather than assuming the watering sections are contiguous with the rest.
- Other care types get no "done today" list; adding one would need its own decision.
