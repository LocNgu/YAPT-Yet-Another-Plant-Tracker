# Product ADR-0040: Water button renders whenever the watering-due row does, not just when a schedule exists

**Status**: accepted

**Date**: 2026-09-18

**Amends**: ADR-0031 ("Watering-due actions are always visible, not gated on due status"). Not
superseded — ADR-0031's own mechanism (the two-action split, the reason-prompt design, "off-schedule
actions ask why", `GAP_AGREEMENT_TOLERANCE` as the single "on schedule" definition) is unchanged; only
the row's outer visibility condition changes again. Unlike ADR-0031 itself — which recorded a matching
"Amended by" back-reference inside ADR-0029/ADR-0030 — ADR-0031 is left byte-for-byte untouched by this
ADR; see the Consequences section for why.

## Context

Issue #704 graduates `PLANT_DETAIL_TABS` — the Plant Detail per-action tabs feature ships
unconditionally, and the classic single-page layout is deleted. That deletion removes `StatsRow`
(and its `StatChip`s) from the app entirely, not just from the tabs layout: `StatsRow`'s watering
`StatChip` was `StatChip`-unconditional (its `onWaterClick` rendered regardless of
`fertilizingIntervalDays`/`wateringIntervalDays`), so a plant with no configured watering interval
still had a one-tap "log a watering" entry point in the classic layout today.

`WateringDueActionsRow` (the tabs layout's Water/Reschedule row, ADR-0031) is gated on
`plant?.wateringIntervalDays != null` as a single unit — Water and Reschedule render together or not
at all. Graduating the tabs layout as the only layout, with that gate unchanged, would silently drop
the one-tap Water action for any plant with no watering schedule set. The only remaining route would be
`+` Log care → Add Care Log → manually select WATER — a real functional regression discovered during
#704's spec pass, not a hypothetical: `StatChip`'s own `stat_label_never` empty state exists precisely
for "no interval configured", and no existing test covered "no interval, tabs layout" before this.

The Water tab's `InlineIntervalSetting` is not an adequate substitute — using it forces the user to
give the plant a recurring schedule as a side effect of wanting to log one watering.

## Decision

Split `WateringDueActionsRow`'s gate: **Water** renders whenever the row itself renders
(`careStatus != null`), independent of `wateringIntervalDays`. **Reschedule** keeps its existing
`wateringIntervalDays != null` gate — rescheduling a due date that doesn't exist is meaningless, so
only Water loses the gate.

`onRescheduleClick` becomes a nullable `(() -> Unit)?` parameter (mirroring the nullable-callback idiom
`StatsRow`'s own `StatChip` already used for `onWaterClick`/`onFertilizeClick`): the caller passes a
non-null lambda only when `plant?.wateringIntervalDays != null`, `null` otherwise, and the composable
renders the Reschedule icon button only when it receives one.

`RescheduleDeltaChip` and `CombinedWaterFertilizeActionRow` keep their current gating unchanged — exact
parity with the `StatChip` being replaced, whose `onWaterClick` was plain (never branched on
`useLiquidFertilizer`), and `rescheduleDeltaDays` is itself only ever non-null when a watering schedule
already exists to be overridden.

## Consequences

- A plant with no configured watering interval keeps a one-tap "Water" action once the classic layout
  (and `StatsRow` with it) is deleted — no regression from today's classic-layout behavior.
- Reschedule stays unreachable without a watering schedule, same as before this change — there is
  nothing to reschedule.
- `WateringDueActionsRow`'s signature changes (`onRescheduleClick: () -> Unit` →
  `(() -> Unit)?`); every call site updates to pass `null` when `wateringIntervalDays == null`.
- ADR-0031 remains `accepted`, not superseded, and is left untouched — no back-reference note is
  added to it, per CLAUDE.md's rule that the only permitted edit to a finalized ADR is its Status
  line. (ADR-0029 and ADR-0030 do carry informal "Amended by ADR-0031" notes from before that rule
  was consistently enforced; reconciling that inconsistency is out of scope here.)
