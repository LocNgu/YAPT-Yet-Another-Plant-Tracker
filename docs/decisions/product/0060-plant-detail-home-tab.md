# Product ADR-0060: Plant Detail gets a Home tab — the landing tab, with watering and fertilizing actions and a summary

**Status**: accepted — "beside its misting list" clause amended by [ADR-0061](0061-manual-misting-retired.md); tab order, `FertilizeDueActionRow` interval-gate and liquid edge-case clauses, and "Not changed: the `+` FAB and Add Care Log" clause amended by [ADR-0062](0062-plant-detail-actions-replace-add-care-log-fab.md)

**Date**: 2026-10-02

## Context

Issue #530 asked for a Home pane on Plant Detail: the hub for the two most common actions, showing when
watering and fertilizing last happened and are next due, with each care pane listing only its own history.
Most of the issue's original requests have since shipped by other routes. Water, Reschedule, Fertilize,
Repot and Photo actions already live inside their own panes (product ADR-0031/0038/0040, #603, #652, #658),
the always-visible `StatsRow` is gone (#704), the Reschedule dialog and the delta chip with tap-to-revert
exist (product ADR-0029/0039, #630), and the `PLANT_DETAIL_TABS` flag graduated (#704, product ADR-0042).
What is still missing is Home itself, the next-watering date (it appears nowhere on the screen except
inside "Why this date?"), and per-pane history: the combined care log still renders under every tab.

Alternatives considered:

- **Keep Water as the landing tab and add nothing.** Rejected. The next watering date has no home, and the
  landing tab would stay a single care type's settings page rather than a plant's overview.
- **A Home tab with no actions, only a summary and the log.** Rejected. The point of a hub is that the two
  everyday actions are one tap away from the landing view; a read-only summary forces a tab switch to act on
  what it just showed.
- **Home with its own Water and Fertilize implementations.** Rejected. Every logging path carries a
  same-day duplicate guard, a date picker, an on/off-schedule reason prompt and adaptive-model side effects.
  A second copy of any of them would drift.
- **Put Repot on the strip's first row instead of Photo, or keep Repot there and demote Photo.** Rejected.
  Photo is used far more often than Repot, and the repotting audit lives on its own page (product
  ADR-0059).

## Decision

**Home is a new first tab and the landing tab.** `PlantDetailTab.DEFAULT = HOME` is the one constant behind
the screen's initial selection, `fromRouteArg`'s unknown-name fallback (a null route arg stays null, the
screen applies the default) and the reset when the row collapses under a hidden tab. Plant List, Calendar
and notifications therefore open on Home. The Care tab and the Repotting overview still open the tab that
matches the task (`TodayCareKind.plantDetailTab()` is unchanged; no Care task maps to Home).

**Strip order is Home · Water · Fertilize · Photo | Repot · Reminders · Issues.** The first four stay on
the collapsed row at the same 25% width; Repot moves behind the chevron. This amends product ADR-0043's tab
set and order, its collapsed row and its orphan-reset target (Water becomes Home). Its width rationale
(every tab keeps the same fixed width, no shrinking and no horizontal scrolling) is unchanged. A deep link
to Repot, Reminders or Issues still expands the row, via `isInCollapsedRow`.

**Attention badge.** Hiding a tab must not hide something that needs attention (product ADR-0043's
rationale), and Repot is now hidden. The collapsed toggle's badge and announced description therefore also
fire for an overdue repot (`PlantCareStatus.isRepottingOverdue`, which includes a plan whose season has
ended), alongside an active issue or an overdue custom reminder. Overdue only: there is no due-soon state.

**Home content, top to bottom.**
1. The Reschedule delta chip, gated exactly as on the Water tab. Home hosts Reschedule, and the chip is its
   only revert affordance.
2. The same `WateringDueActionsRow` (Water unconditional per product ADR-0040; Reschedule only when a
   schedule-computed due date exists).
3. One `FertilizeDueActionRow`, gated on a fertilizing interval. It already relabels to "Water + Fertilize"
   for a liquid-fertilizer plant, so Home is the combined action's entry point.
4. A summary card in the per-tab insights style with Last watered and Next watering, plus Last fertilized
   and Next fertilizing only when a fertilizing interval is set. Values read as relative text followed by the
   absolute date. Suspended watering and dormant fertilizing read "Dormant" (never a stale due date); a
   dormant watering cadence shows its active due date; an out-of-season fertilizing date is the shifted one;
   a plant that was never watered or fertilized reads "Never watered" / "Never fertilized"; a plant with no
   watering interval has no Next watering row. The card hosts no settings controls (product ADR-0023).
5. The combined care history.

**The Water tab no longer carries the combined button.** #652 gave a liquid-fertilizer plant's Water tab a
second "Water + Fertilize" button (`CombinedWaterFertilizeActionRow`) so the combined action was reachable
without switching tabs. With Home and the Fertilize tab both hosting that action through
`FertilizeDueActionRow`, the Water tab keeps only the chip and the plain Water/Reschedule row: one combined
entry point per pane, and no pane showing two combined buttons. The component and its test tag are deleted. The
accepted edge case: a liquid-fertilizer plant with no fertilizing interval now has no combined button anywhere
(`FertilizeDueActionRow` stays gated on the interval), the same as a non-liquid plant without a schedule; the
`+` FAB and Add Care Log remain.

**Home duplicates the Water entry point, knowingly.** Product ADR-0031 removed the old watering chip because
it duplicated the Water tab's button. Home re-adds a Water button, but the two are never on screen at once
(one tab renders at a time), and everything runs through the same shared composables and handlers, so there
is no second code path: the same date picker, reason prompt, duplicate guard and screen-level dialog state.

**History placement (delivered in a second PR).** The combined log, hiding `CareType.CHECK` rows and
collapsed to five, renders only on Home, and the Water tab gains a WATER list beside its misting list, so
each care pane lists its own type. Prune, Note, Photo and reminder-done entries appear only in Home's
combined log. This amends the care-history placement clause of technical ADR-0018 ("the unified care-history
list stays below the tab content"). The first PR leaves the log under every tab, so Home already shows it and
no intermediate state is worse than before.

Technical ADR-0018's "StatsRow stays above the tab strip" and `PrimaryTabRow` wording have been stale since
#704 and #590; its Status line records that alongside the placement amendment.

## Consequences

- Plant Detail opens on a plant overview rather than on the Water settings, and the next watering date is
  visible again without opening "Why this date?".
- Repot is one extra tap away, but cannot go unnoticed when overdue (badge) and keeps its deep links.
- A second Water button exists, in a different tab from the first; the Water tab's liquid-fertilizer second
  button is gone (see above). The cost is a few extra lines of screen
  wiring, not logic.
- `PlantDetailScreenTest`'s default tab is no longer Water; tests that exercise Water-pane controls open on
  that tab explicitly.
- Not changed: the `+` FAB and Add Care Log (#532, blocked on this), forward-only rescheduling (product
  ADR-0039), and any repot, reminders or issues summary on Home (the badge is the only signal).
