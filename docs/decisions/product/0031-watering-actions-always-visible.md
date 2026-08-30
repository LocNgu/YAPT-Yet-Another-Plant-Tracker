# Product ADR-0031: Watering-due actions are always visible, not gated on due status

**Status**: accepted

**Date**: 2026-08-29

**Amends**: ADR-0029 (introduced the due-status gate on the watering-actions row) and ADR-0030
(narrowed the row to two buttons but kept the same gate). Neither is superseded — the mechanism both
describe (the two-action split, the reason-prompt design, "off-schedule actions ask why") is
unchanged; only the visibility condition changes.

## Context

ADR-0029 introduced Plant Detail's "watering-due surface" — a row of buttons (Water / Still moist /
Reschedule watering) shown only while `status.isOverdue || status.isDueSoon` was true, i.e. only on or
after the plant's due date. ADR-0030 narrowed the row to two buttons (Water / Reschedule watering,
each asking "why" when the action is off schedule) but carried the same gate forward unchanged.

Issue #603 (bug report) found that this gate meant `WateringDueActionsRow` — both call sites, the
classic single-page layout and the tabs layout's Water tab — was omitted from the layout entirely, not
merely disabled, on every day before a plant's due date. `PlantDetailViewModel.requestReschedule()`
is only ever invoked from this row's `onRescheduleClick`; there is no other entry point anywhere in
the app. The impact was asymmetric between the two buttons: Water had a fallback (the always-visible
watering `StatChip` in `StatsRow` also called the shared `requestWater()` helper), but Reschedule did
not. The entire reason-prompt → date-dialog flow — including the "Soil still moist" adaptive
observation path that feeds the model — was completely unreachable while a plant wasn't yet due. Not
a minor inconvenience: a full outage of that flow for that state.

The issue itself flagged that removing the gate was not an incidental fix but a product-behavior
expansion beyond what ADR-0029/ADR-0030 document — neither ADR, nor ADR-0027 (the notification's
Still-moist action, which fires only once a reminder is already due), discusses showing these actions
*before* a plant is due — and asked for explicit human confirmation before implementation. That
confirmation is recorded in issue #603's spec-clarification comment.

## Decision

Drop the `status.isOverdue || status.isDueSoon` clause entirely at both `WateringDueActionsRow` render
sites. The row now renders whenever `plant?.wateringIntervalDays != null`, unconditionally — the same
gate the row already used for "does this plant even have a watering schedule", with no due-status
condition layered on top. "Did water go in, or not?" (Water) and "I'm away / not now" (Reschedule) are
both actions a user may reasonably want to take on any day, not only once the plant is overdue.

Two companion decisions ship in the same change, made in the same issue's spec-clarification comment,
since Water becoming always-reachable via this row made the classic layout's always-visible watering
`StatChip` (in `StatsRow`) a redundant second control for the same action — specifically in the tabs
layout, where the row and the chip could appear on screen together:

- **`StatsRow` is removed from the Plant Detail tabs layout only** (`PLANT_DETAIL_TABS` on), not the
  classic layout. The classic layout has no tab structure to relocate the fertilizing action into, so
  removing `StatsRow` there would leave fertilizing with no entry point at all; it stays exactly as
  before.
- **The Fertilize tab gains a new always-visible `FertilizeDueActionRow` button**
  (`WateringDueActions.kt`), replacing the fertilizing `StatChip`'s `onFertilizeClick` entry point in
  the tabs layout. A single button, not a two-button row — fertilizing has no "reschedule" concept.
  Gated on `plant?.fertilizingIntervalDays != null` only, mirroring the `StatChip` it replaces (which
  was never due-status-gated either).

## Consequences

- **Reschedule watering (and the "Soil still moist" adaptive-observation path it leads to) is now
  reachable on any day**, not only once a plant is overdue or due today. This is the change the issue
  exists to make: a control with no other entry point was previously unreachable for most of a plant's
  watering cycle.
- **This does not reopen ADR-0029/ADR-0030's actual mechanism.** The two-action split, the "ask why
  only when off schedule" rule, `GAP_AGREEMENT_TOLERANCE` as the single definition of "on schedule",
  and the reuse of `CareLog.wateringFeedback` for the reason are all unchanged — only the *visibility
  condition* around the row changes. Both ADRs remain `accepted`, not superseded; each now carries a
  one-line "Amended by ADR-0031 (visibility gate)" note under its own Consequences section.
- **`StatsRow`'s watering/fertilizing `StatChip`s no longer appear in the tabs layout at all** — a
  layout-dependent removal, not a blanket one. A Compose UI regression test
  (`statsRowStatChips_areAbsentFromTabsLayout`) asserts their text is absent from the tabs layout to
  guard against an accidental re-add.
- **Removing `StatsRow` from the tabs layout also removed its "last watered X ago" / "last fertilized
  X ago" at-a-glance text**, which nothing initially replaced. Fixed in the same change:
  `careTypeInsightItems(...)`'s `lastAtLabel` parameter is now populated for both the Water and
  Fertilize tabs (`R.string.insight_last_watered` / `R.string.insight_last_fertilized`, mirroring the
  existing `R.string.insight_last_repotted` pattern), so `TabInsightsCard` shows that information again
  via `DateUtils.formatRelative()` — no new date-math, no schema change.
- The row's framing/copy ("Water" / "Reschedule watering") is unchanged — the issue asked whether it
  needed adjusting now that the row is no longer due-gated, and the answer (per the spec-clarification
  discussion) is no: both actions read naturally regardless of due status.
