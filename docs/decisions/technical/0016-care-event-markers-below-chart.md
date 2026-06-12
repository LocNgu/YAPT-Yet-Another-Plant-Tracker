# ADR-0016: Care event markers rendered as a below-chart strip, not a Vico layer

**Status**: accepted

**Date**: 2026-06-12

## Context

Issue #231 asks for non-WATER care events (REPOT, MIST, PRUNE, etc.) to appear as visual markers on the watering history chart so users can correlate care actions with interval changes.

Two implementation approaches were evaluated:

### Option 1: Vico `ColumnCartesianLayer`

Add a second `CartesianLayer` to the existing `rememberCartesianChart(...)` call. Each column at a month index would represent events in that month.

Problems:
- Both layers share the y-axis by default. Watering intervals are measured in days (e.g. 7–21 d); a fixed column height of `y=1` would be nearly invisible against that scale.
- A separate right-side `VerticalAxis` for the column layer adds UI noise and is unnecessary.
- ADR-0004's NaN constraint must also apply to the column series: months with no events must be omitted rather than represented as `y=0` or `y=NaN`, adding complexity.
- Per-CareType multi-series coloring requires additional series entries per month and careful `rangeProvider` coordination.

### Option 2: Below-chart care event strip (chosen)

Render a `CareEventMarkersRow` composable directly below the chart's `Column` in `WateringHistoryChart`. The strip shows a `LazyRow` of `SuggestionChip`s (icon + label + date) for every non-WATER event within the selected time range, sorted chronologically.

Advantages:
- No risk of NaN crashes or y-axis conflicts — entirely separate from Vico.
- Does not overlap or obscure the watering interval line.
- Time-range filtering reuses the existing `rangeStartMs` / `now` values computed in the composable.
- Hidden when no events exist in range (zero-crash guarantee).
- Straightforward to unit-test via the pure `computeCareEventMarkers()` function.

## Decision

Use Option 2. A new pure function `computeCareEventMarkers(nonWaterLogs, rangeStartMs, now)` bins events into `CareEventMarker(monthIndex, careType, timestamp)` and returns them sorted by timestamp. The `CareEventMarkersRow` composable renders the strip using the existing `CareType.icon()` and `CareType.labelRes()` extension functions from `EnumResources.kt`.

## Consequences

- Care events are visible to the user in the same scroll area as the chart.
- Scroll sync between the Vico chart and the care event strip is not attempted; the strip shows all in-range events without positional alignment to the chart x-axis.
- Future improvement: the `monthIndex` field on `CareEventMarker` is available for a grouped-by-month view or Vico decoration approach if scroll sync is later solved.
