# Product ADR-0055: Care plant grid, Watering date sub-groups, and a long-press quick-action menu

**Status**: accepted

**Date**: 2026-09-28

## Context

Product ADR-0054 shipped the Care tab as sections by care type, one photo row per task with a relative
due label and action buttons stacked under the name, long-press multi-select with one bulk completion,
and a temporary developer flag that regrouped the same queue by plant. After using it, the owner wanted
a denser, more visual layout (#842). The queue itself was not in question: the aggregator, its
Overdue-through-three-days horizon, and every single-task action's behaviour and writes stay as ADR-0054
described them.

Alternatives considered:

- **Keep bulk selection alongside a per-tile menu.** Rejected. Long-press is the only gesture that
  reaches a tile's quick actions, so selection would need a second, hidden entry point. Bulk
  completion also skips the watering reason prompt and the interval suggestion that the single-task
  path shows.
- **Keep the plant-grouped layout behind its flag.** Rejected. Product ADR-0042 says a flag never
  outlives its experiment. The grid is the chosen direction, so the flag graduates by deleting it and
  the losing presentation together.
- **Persist collapsed sections across restarts.** Rejected. A group collapsed once and forgotten
  would hide due care on a later day, the exact failure the queue exists to prevent.
- **Show a due date on every tile.** Rejected. The Watering sub-groups already carry the date bucket,
  and overdue is carried by the outline; per-tile text competes with the photo for a small square.

## Decision

**Grid.** Every section is a grid of square tiles: the plant's photo (with the existing placeholder) and
its name underneath, at most two lines with an ellipsis. Width decides the column count, from two to
four per row (140dp minimum tile width, capped at four so a tablet does not pack five or more small
tiles); a 320dp screen shows two. Section and sub-group headers span the full width.

**Second line.** Only custom-reminder tiles (the reminder name), issue-treatment tiles ("Treat …") and
the combined water-and-fertilize tile ("Water & fertilize") carry a second line; every other tile is
just the photo and the plant name.

**Watering sub-groups.** Watering, and only Watering, splits into **Overdue / Today / Next 3 days**,
taken directly from the aggregator's `TodayTaskBucket` (every `Upcoming` bucket is already inside the
three-day horizon, so no date math is repeated). Empty sub-groups are hidden. Every other section is
flat in the canonical queue order (due instant, plant name, task id).

**Headers and counts.** Headers are larger (`titleMedium`), read `label · count` with a chevron, and the
count is the number of **distinct plants**, not tasks. A plant with two custom reminders is two tiles and
one plant.

**Collapse.** Tapping a section or sub-group header collapses or expands it. The state is per session
(`rememberSaveable`): it survives tab switches and rotation and resets when the app is restarted. The
header exposes its state to TalkBack through `stateDescription` (Expanded/Collapsed), `heading()`, and an
Expand/Collapse click label.

**Overdue.** Tiles carry no due-date text. An overdue tile, in any section, gets a thin `OverdueRed`
outline around its photo and "Overdue" at the start of its content description. Every tile's content
description names the plant and the task ("Fern, watering"; "Overdue, Fern, watering").

**Long-press quick-action menu.** Tapping a tile opens the plant (Plant Detail's default tab until the
Care-to-tab mapping in #843 lands). Long-pressing a tile opens a `DropdownMenu` anchored to it with only
that task's actions:

| Task kind | Menu items |
|---|---|
| Water | Water, Reschedule |
| Water and fertilize | Water & fertilize, Reschedule |
| Fertilize | Fertilize |
| Repot | Repot… |
| Custom reminder, issue treatment | Mark done |
| Photo | Take photo |

Each item calls the handler the removed inline button used, so the watering reason prompt, the interval
suggestion dialog, the reschedule dialog, the repot date picker and the camera path are reused, not
reimplemented. There are no inline action buttons on tiles. Because long-press is not discoverable to
screen-reader users, the same actions are exposed as semantics `customActions`, and the tile has an
`onLongClickLabel`.

**Bulk completion is removed.** Care has no selection mode: no selection state, no "Complete selected"
bar or result snackbar, no `onSelectionModeChanged`. `QuickLogUseCase.completeTodayTasks()` and
`BulkCompletionResult` are deleted (`completeCustomReminder()` stays; it is the single "Mark done"
write, and Plant List's unrelated `bulkLog()` stays). The outer bottom navigation never hides while
Care is showing; it still hides for Plant List's own selection mode.

**The plant-grouped layout graduates by deletion.** The `today_group_by_plant` developer flag, the
`plantSections()` renderer and its tests are removed, and `FeatureFlagRegistry.all` is empty again, per
product ADR-0042.

**Amends product ADR-0054.** Only its presentation and interaction clauses change:

- the bottom-navigation clause "it also hides while Care or Plants is in selection mode" now applies to
  Plants only;
- the row description (each row shows its own relative due label with overdue rows highlighted, a
  larger photo, action buttons, long-press selection with tinted rows and hidden controls) is replaced by
  the tile, outline, sub-group, and long-press menu decisions above;
- the bounded bulk completion paragraph and the matching Consequences bullet are removed;
- the `today_group_by_plant` paragraph is settled: the experiment graduated and the flag is deleted.

Everything else in product ADR-0054 stands: Care as the first tab and start destination, the section
order, the queue's sources and horizon, reuse of the existing quick-care behaviour, the day-change
ticker, and the absence of a Room or backup schema change.

## Consequences

- Completing several tasks means acting on each tile in turn; there is no batch path. That is a
  deliberate trade for a per-task menu that always shows the reason prompt or suggestion the plant's
  own schedule calls for.
- The Care screen no longer changes the shell chrome, so the bottom bar and top bar are stable while
  it is showing.
- A device that ran the flag build keeps an orphan `feature_flag_today_group_by_plant` boolean in
  DataStore. It is never read again and needs no migration (flags never touch the database schema).
- No Room schema or `.yapt` backup change. `TodayQueueAggregator` is untouched.
- Tests that covered selection and bulk completion are removed with the behaviour rather than weakened;
  menu, sub-group, collapse, overdue, and accessibility tests replace them, and the single "Mark done"
  write gained a direct test.
