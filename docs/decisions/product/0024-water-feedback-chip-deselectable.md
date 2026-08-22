# Product ADR-0024: Water feedback chips are deselectable; Log always writes, even with no feedback

**Status**: superseded by [ADR-0027](0027-check-reminders-still-moist-action.md) (the three-way chip this ADR made deselectable is itself replaced by a single optional flag); until then, accepted, partially supersedes [ADR-0016](0016-quick-water-bottom-sheet.md) (chip-selection clause only) and partially supersedes [ADR-0017](0017-quick-fertilize-bottom-sheet.md) (the "written with the selected `WateringFeedback`" always-selected assumption only)

**Date**: 2026-08-17

## Context

ADR-0016 introduced `WaterFeedbackBottomSheet`: three FilterChips (TOO_SOON / JUST_RIGHT / TOO_LATE) with JUST_RIGHT
pre-selected, and a "Log" button. The chip selection was modeled as non-nullable (`WateringFeedback`), so exactly
one chip was always selected — tapping the already-selected chip was a no-op.

Issue #549 reported this as reading like a bug: every other single-select chip group in the app (e.g. the feedback
chips on `AddCareLogScreen`) is deselectable by tapping the selected chip again, writing `null` feedback if nothing
ends up selected. The quick-water sheet was the only place this toggle didn't work, because `AddCareLogScreen`
already models `selectedFeedback` as `WateringFeedback?` and toggles it (`selectedFeedback = if (selectedFeedback
== feedback) null else feedback`), while `WaterFeedbackBottomSheet` never adopted that pattern.

`CareLog.wateringFeedback` has always been nullable in the Room schema (WATER logs with no feedback are already
possible via the full Add Care Log screen), so no data-model change is needed to support this.

`WaterFeedbackBottomSheet` is also reused by the combined quick-water-fertilize button for liquid-fertilizer plants
(ADR-0017), which describes the WATER log as "written with the selected `WateringFeedback`" — that phrasing assumed
a chip was always selected. This ADR's deselect behavior applies equally to both entry points since they share the
same component.

## Decision

`WaterFeedbackBottomSheet`'s `selectedFeedback` becomes `WateringFeedback?`, still pre-selected to JUST_RIGHT when
the sheet opens. Tapping the currently-selected chip clears it to `null` instead of being a no-op, mirroring
`AddCareLogScreen`'s existing toggle pattern exactly.

The "Log" button stays enabled regardless of selection state — it is never disabled when nothing is selected. It
writes the care log with `wateringFeedback = null` when nothing is selected, the same no-feedback state the full
Add Care Log screen already supports for WATER logs.

`onLog` becomes `(WateringFeedback?) -> Unit`. `PlantListViewModel.quickWaterWithFeedback`/
`quickLiquidFertilizeWithFeedback`, `CalendarViewModel.quickWaterWithFeedback`/`quickLiquidFertilizeWithFeedback`,
and `PlantDetailViewModel.quickWater`/`quickLiquidFertilize` all take a nullable feedback parameter and pass it
through unchanged to `QuickLogUseCase`. The adaptive interval suggestion is simply skipped when feedback is null
(mirroring `AddCareLogViewModel.computeSuggestedInterval`'s existing `selectedFeedback ?: return null` guard) —
there is no new fallback to JUST_RIGHT.

## Consequences

- The quick-water sheet's chip group now behaves consistently with every other deselectable chip group in the app.
- Users can log a watering from the quick-water sheet with no feedback recorded, same as they already could from
  the full Add Care Log screen — this was previously only reachable via the longer flow.
- No adaptive-interval suggestion is computed for a null-feedback quick-water/quick-liquid-fertilize log, same as
  the existing null-feedback behavior on the full Add Care Log screen.
- No Room migration — `CareLog.wateringFeedback` was already nullable.
- ADR-0016's "JUST_RIGHT pre-selected" default and the 2-tap fast path for the common case are unchanged; only the
  no-op-on-retap behavior is fixed.
