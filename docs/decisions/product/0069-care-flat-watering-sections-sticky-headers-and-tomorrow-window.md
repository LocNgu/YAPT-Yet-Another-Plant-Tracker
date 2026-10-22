# Product ADR-0069: Care shows watering through tomorrow, other kinds through today, with flat sticky section headers

**Status**: accepted

**Date**: 2026-10-22

## Context

Product ADR-0054 fixed the Care queue at Overdue through the end of the third local calendar day, for every task
kind, and product ADR-0056 split Watering (and only Watering) into **Overdue / Today / Next 3 days** sub-groups under a
parent "Watering" header. With many plants the three-day bucket became too long to be useful, and because every
header was an ordinary grid item, nothing stayed pinned: once a section's tiles filled the screen the user could not
tell which section they were in (#931).

Alternatives considered:

- **Keep three days for everything but shrink the Watering bucket to tomorrow.** Rejected. A task kind that is
  still shown three days out but not bucketed would need its own presentation. Non-watering tiles carry no due-date
  text (product ADR-0056), so a fertilizing or custom-reminder tile due in three days would read the same as one due
  today.
- **Shrink one shared window to tomorrow for every kind.** Rejected. Watering is the only care the user routinely
  does a day early; fertilizing, repotting, reminders and photos are not worth acting on before their day, and
  showing them a day ahead dilutes the "due now" list.
- **Add a look-ahead rule for liquid-fertilizer plants** so fertilizing due tomorrow still combines with watering
  due tomorrow. Rejected as unneeded complexity: the existing combine test is unchanged, and fertilizing is picked
  up at the next watering or on Plant Detail.
- **Pin headers with an overlay drawn from the grid state, or move to a `LazyColumn` of chunked rows.** Rejected.
  `LazyGridScope.stickyHeader` exists in the Compose Foundation version the BOM resolves, so the pinned header is
  the same item as the scrolling one: no duplicate heading in the semantics tree and no second layout to keep in
  step with `CareGridCells`.

## Decision

**Per-kind window.** On Care's default path watering (WATER and the combined water-and-fertilize task) shows
**Overdue / Today / Tomorrow**, and every other kind (fertilizing, issue treatments, custom reminders, repotting,
photos) shows only **Overdue / Today**. Something due tomorrow that is not watering appears on the day. A repot plan
whose season starts tomorrow is not on Care until it starts. Accepted trade-off: a user who waters early may meet
that plant's fertilizing only at the next watering or on Plant Detail.

The aggregator keeps `TodayQueueInput.horizonEnd` as the watering bound (default today + 1) and adds
`nonWateringHorizonEnd` for the other kinds, which shares `horizonEnd` unless set. Only
`TodayCareRepository.observeQueue()` with no caller horizon sets it to today. An explicitly widened horizon
(`observeQueue(horizonEnd)`, the sitter and Away flows, a direct `TodayQueueInput(horizonEnd = …)`) bounds every kind
alike, so it is never clipped.

**Liquid fertilizer.** No new rule. A liquid-fertilizer plant still gets one combined task when watering falls in the
horizon and its fertilizing task exists and is due no later than watering. The fertilizing task is now built under the
narrower window, so fertilizing due tomorrow no longer combines (the user sees a plain watering tile), and fertilizing
overdue with watering two or more days away shows no task. Both are accepted.

**Flat watering sections.** Watering is no longer a parent with children. Its buckets are three top-level sections,
in this order, each hidden when empty: **Watering · Overdue · N**, **Watering · Today · N**, **Watering · Tomorrow · N**
(N = distinct plants). The other sections keep `Fertilizing · N` and so on, and every header has the same style.
`TodayWateringGroup` is `OVERDUE / TODAY / TOMORROW`, mapped 1:1 from `TodayTaskBucket` (any `Upcoming` is tomorrow,
which holds because Care's own screen never widens the horizon), so the UI does no date math.

**Collapse.** Each header collapses on its own, with the heading, Expanded/Collapsed `stateDescription` and
Expand/Collapse click label unchanged. The state stays per session (`rememberSaveable`). Keys are `watering:OVERDUE`,
`watering:TODAY`, `watering:TOMORROW` and `section:<NAME>`; `section:WATERING` is no longer emitted and stale keys are
inert (the set does not outlive the process).

**Sticky headers.** Every section header is pinned while its tiles scroll, using `LazyGridScope.stickyHeader`. It pins
at the top of the grid area, under the Away banner (and a future week strip), has an opaque background, and stays
tappable. Tapping the pinned header collapses its section and then scrolls the grid so that header is the first
visible item (otherwise the viewport would be left on tiles that no longer exist). A header already on screen
collapses without scrolling.

**Copy.** The caught-up state reads "You're all caught up. Nothing is due today and no watering is due tomorrow."
The "Next 3 days" string is removed.

**Supersedes.** From product ADR-0054: the horizon "through the end of the third local calendar day" and the
statement that the horizon applies to every task kind. From product ADR-0056: the Watering "Overdue / Today /
Next 3 days" sub-group clause, its sub-group collapse wording, and the "Overdue-through-three-days horizon" wording.
Everything else in both stands, including tiles without due-date text and the long-press menu.

## Consequences

- The Care list is shorter, but a non-watering task due tomorrow is invisible on Care until its day; Plant Detail,
  Calendar and notifications still show it.
- Care now has two upcoming bounds in the aggregator. Any new caller that constructs `TodayQueueInput` directly and
  wants the Care shape must set `nonWateringHorizonEnd` itself; the default shares `horizonEnd`, which is what the
  sitter and Away flows want.
- The per-kind window is a Care presentation choice, not a schedule change: due dates, dormancy, away handling and
  adaptive watering are untouched, and there is no Room schema or `.yapt` backup change.
- Sticky headers depend on the Foundation `stickyHeader` API. If a future Foundation release changes it, the fallback
  is an overlay derived from the grid state.
- `observeQueue(horizonEnd)`'s signature and the sitter flow's date logic are unchanged.
