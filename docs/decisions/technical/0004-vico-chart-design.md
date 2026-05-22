# ADR-0004: Vico chart — empty months omitted from series, labels in ExtraStore, conditional autoscroll

**Status**: accepted

**Date**: 2024-01-01

## Context

The watering history chart in `PlantDetailScreen` uses Vico (`com.patrykandpatrick.vico:compose-m3`). Three non-obvious constraints shaped its implementation:

### 1. Empty months crash if represented as y=0 or NaN

Vico's `LineCartesianLayer.updateMarkerTargets` throws a `NaN`-related exception if any data point has a `Float.NaN` y-value. Using `y=0` for months with no waterings creates false flat segments that mislead the user (zero days between waterings is impossible).

### 2. Labels and data must be updated atomically

Month labels are stored in Vico's `ExtraStore` and read during chart drawing. If labels and data points are updated in separate transactions, a draw pass can occur between the two updates, producing mismatched labels (e.g., label for month N is drawn next to the data point for month N+1).

### 3. Autoscroll on every data update disrupts manual browsing

Vico supports `initialScroll = Scroll.Absolute.End` and `autoScroll`. If autoscroll fires on every `StateFlow` emission — including unrelated care log changes (fertilize, prune, photo) — the chart yanks back to the right edge while the user is browsing earlier history.

## Decision

**Empty months**: Only months that have actual watering interval data are added to the line series as data points. Empty months are omitted from the series entirely. `CartesianLayerRangeProvider.fixed(0, totalMonths-1)` keeps the full month range in the chart's coordinate space, so x-axis labels render for every month even when no data point exists for that month.

**Label atomicity**: Month labels are written into `ExtraStore` inside the same `runTransaction { ... }` block as the data points. Labels and data are always read from the same model snapshot during drawing.

**Conditional autoscroll**: `AutoScrollCondition` compares the previous model's `maxX` with the new model's `maxX`. Autoscroll fires only when `maxX` increases (new data added at the right edge) or changes (time range switched). Unrelated emissions that don't change `maxX` do not trigger autoscroll.

See `WateringHistoryChart.kt` for the implementation.

## Consequences

- The chart never crashes on sparse watering data.
- Labels are always consistent with the data points drawn beside them.
- Users can scroll left to browse history without the chart snapping back on unrelated events.
- Gaps in the line are visible when months are skipped, which accurately represents the data (no watering recorded that month).
- If Vico's API changes in a future version, these three workarounds should be re-evaluated: the `NaN` restriction, the `ExtraStore` atomicity requirement, and the `AutoScrollCondition` API.
