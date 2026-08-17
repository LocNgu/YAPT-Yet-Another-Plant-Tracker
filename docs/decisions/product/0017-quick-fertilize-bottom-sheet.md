# Product ADR-0017: Combined quick-water-fertilize button opens a feedback bottom sheet

**Status**: accepted, partially supersedes [ADR-0016](0016-quick-water-bottom-sheet.md) (the "quick-fertilize is unchanged" clause), partially supersedes [ADR-0008](0008-liquid-fertilizer-auto-pairs-water-log.md) (the hardcoded JUST_RIGHT feedback on the quick-fertilize path), partially superseded by [ADR-0024](0024-water-feedback-chip-deselectable.md) (the "written with the selected `WateringFeedback`" always-selected assumption only)

**Date**: 2026-06-11

## Context

ADR-0016 introduced `WaterFeedbackBottomSheet` for the standalone quick-water button on PlantCard, allowing users to record feedback before committing a watering log. That ADR explicitly left the combined quick-water-fertilize button (shown for plants with `useLiquidFertilizer = true`) unchanged: tapping it still committed both logs silently with hardcoded `WateringFeedback.JUST_RIGHT`.

This created an inconsistency:

| Button | Behaviour after ADR-0016 |
|---|---|
| Water drop (quick-water) | Opens `WaterFeedbackBottomSheet` → user picks feedback → logs water → interval suggestion fires |
| Water + fertilize (liquid plants) | Logs both immediately with hardcoded JUST_RIGHT → no feedback, no interval suggestion |

Liquid-fertilizer users water their plants every time they fertilize, so the combined button is their primary watering entry point. Silently hardcoding JUST_RIGHT here undercuts the adaptive interval to the same degree as it did for standalone waterings before ADR-0016.

## Decision

Tapping the combined quick-water-fertilize button on a liquid-fertilizer PlantCard now opens `WaterFeedbackBottomSheet` before committing any logs. The sheet title is "Water & fertilize [plant name]?" (via `water_fertilize_feedback_sheet_title` string resource) to make clear that both logs will be saved.

After the user taps Log:

1. A FERTILIZE log is written with `FertilizerType.LIQUID` and no feedback.
2. A WATER log is written with the selected `WateringFeedback` at the same timestamp.
3. `wateringDueDateOverride` is cleared on the plant if set.
4. The adaptive interval suggestion fires if the computed suggestion differs from the stored interval, exactly as it does after `quickWaterWithFeedback()`.
5. The "Watered and fertilized [plant]" snackbar is shown.

Dismissing the sheet without tapping Log writes no logs.

The 2-tap happy path (button → Log with JUST_RIGHT pre-selected) is preserved.

## Consequences

- Liquid-fertilizer users can now record TOO_SOON or TOO_LATE feedback from the plant list without navigating to Add Care Log.
- The adaptive interval suggestion fires for every quick-fertilize as well as every quick-water, keeping suggestions accurate for the most common liquid-fertilizer watering path.
- The combined button now requires 2 taps (button → Log) instead of 1 for JUST_RIGHT users. This is the same tradeoff accepted for the standalone quick-water button in ADR-0016.
- `PlantListViewModel` gains `quickLiquidFertilizeWithFeedback(plantId, feedback)`.
- `PlantListScreen` gains a second `WaterFeedbackBottomSheet` gated on `liquidFertilizeFeedbackPlant != null`.
- The existing `quickLog(CareType.FERTILIZE)` path (for solid-fertilizer plants) is unchanged.
