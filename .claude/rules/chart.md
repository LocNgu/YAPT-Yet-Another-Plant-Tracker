---
description: Vico watering-history chart internals and care-event markers
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/plantdetail/**chart**"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/plantdetail/**Chart**"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/components/**Chart**"
---

# Watering-history chart rules (Vico) — technical ADR-0004

Design chosen: range chips (1M/3M/6M/12M/All), not unified zoom (ADR-0004). Empty state when < 2 logs; a single
point (2 total waterings) renders as a circle. `now` is keyed on `wateringLogs` so a freshly-logged watering
appears immediately without navigating away.

## Data model
- `computeWateringIntervals()` — calendar-month buckets; predecessor-anchor so infrequent waterers get in-window
  points; each `daysSincePrevious` is `.coerceAtLeast(1f)` so a sub-day watering plots/averages as 1 day (keeps the
  `ChartLegend` "Average interval" ≥ 1 with no separate clamp) (#446).
- The **line plots one point per individual `WateringInterval`** (fractional `monthIndex`, same formula as
  `computeWaterEventMarkers`) — not monthly averages — so each water-drop icon sits exactly on the line. Vico's own
  pill-dot `PointProvider` was removed; `HorizontalAxis.ItemPlacer.aligned(1)` keeps axis ticks at integer months;
  `rangeProvider` maxX = `totalMonths - 0.001` for last-day waterings (#362/#366).

## Care-event markers (`CareEventDecoration`, Vico `Decoration` API)
- Per-type Material icons drawn at the bottom, day-level precision within each month column; same-day events stack;
  proximity clustering groups icons within 14 dp (`clusterMarkersByCx`, `internal data class PositionedMarker`) (#231/#355/#359).
- The connecting line is a smooth **Catmull-Rom cubic spline** (`internal fun catmullRomSegments()` →
  `Path.cubicTo`); Vico's own line is transparent so smoothing applies to the per-event canvas polyline.
- Tap → `EventMarkerDialog`: `CareEventDecoration` records each drawn icon's canvas position in a plain
  `var drawnMarkers: List<DrawnMarkerInfo>` (main-thread only, no `MutableState`); `detectTapGestures` hit-tests
  within 28 dp (#363).

## Axes
Start `VerticalAxis.ItemPlacer.step { yStep.toDouble() }` where `yStep = computeYAxisStep(yMax)` (whole number, ≲ 6
ticks) — replaces Vico's default, which placed fractional ticks that collapsed into duplicate "0d"/"1d" labels.
`dayFormatter` uses `roundToInt()`, not `toInt()` (#446).

## Gotchas
Label/data sync: use `ExtraStore` inside the transaction so labels + data stay atomically consistent. Auto-scroll:
`initialScroll` is one-shot — pair with `autoScroll` + a custom `AutoScrollCondition` to re-snap on every model change.
Completes #125 (12M averaging superseded by per-event points; unified zoom rejected per ADR-0004).
