---
description: Vico watering-history chart internals and care-event markers
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/ui/components/*Chart*.kt"
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
- `computeCareEventMarkers()` explicitly excludes both `CareType.WATER` (its own dedicated line/icon series) and
  `CareType.CHECK` (#570, "Still moist" observations) — `careTypeColors` has no entry for `CHECK` either. CHECK
  entries still appear in the plain care-history list; only the chart filters them, and does so explicitly (not by
  relying on `iconBitmaps[marker.careType]` silently returning null, which would otherwise still consume a
  cluster/stack slot without drawing anything).
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

**x-step inference breaks on dense, irregular data (#579 follow-up fix).** Without an explicit `getXStep` on
`rememberCartesianChart(...)`, Vico infers it as the GCD of every consecutive x-delta in the series
(`CartesianChartModel.getDefaultXStep`). `WateringHistoryChart.kt`'s sparse per-event points get away with this,
but `SeasonalWateringCurveChart.kt`'s ~365 daily-sampled points don't: unequal month lengths (28-31 days) mean the
day-within-month fractions share no common divisor above the 4-decimal rounding quantum, so the inferred step
collapses to ~0.0001 instead of the intended 1-month unit — `HorizontalAxis.ItemPlacer.aligned(spacing = { 1 })`
then places ticks far too densely, and every one rounds to the same month label. Fix: pass
`getXStep = { _, _, _ -> 1.0 }` explicitly whenever the x-coordinate scheme's "1 unit" has a known fixed meaning
(here, 1 calendar month) rather than trusting the auto-inferred GCD — cheap insurance for any future dense,
evenly-defined-but-numerically-irregular series in a Vico chart.

## Seasonal watering curve preview chart (`SeasonalWateringCurveChart.kt`, #579)
A second, much smaller Vico chart — own file, not a scaled-down `WateringHistoryChart`, since that one is too
coupled to `CareLog` markers/zoom/range-chips. Built from the same primitives (`CartesianChartHost`,
`rememberLineCartesianLayer`, `rememberM3VicoTheme`), but the data is a pure daily sample of
`SeasonalWatering.season()` across a year (`domain/schedule/SeasonalWateringCurveSampler.kt`), not care-log history:
- **x-axis**: 365/366 daily-sampled points, x-coordinate is the same fractional month-index scheme as
  `WateringHistoryChart.kt` (`monthIndexFor()`, `HorizontalAxis.ItemPlacer.aligned(spacing = { 1 })`) so ticks land
  on month boundaries Jan…Dec — this is a *generic* calendar year, not tied to real `CareLog` timestamps. The
  day-within-month fraction itself (`ChartMath.kt`'s `fractionalDayOfMonth()`, 4-decimal-rounded per Vico's GCD
  precision limit) is shared with `computeWaterEventMarkers()`'s per-event x-coordinate — `monthIndexFor()` adds
  the calendar month (Jan=0) on top, `computeWaterEventMarkers()` adds months-since-range-start instead.
- **y-axis**: raw multiplier, **fixed** `0.5×`–`1.5×` (spans `SeasonalAmplitude.STRONG`'s bounds) regardless of the
  currently selected amplitude, so switching Off/Mild/Standard/Strong visibly changes the curve's *height* within a
  constant frame rather than rescaling the axis each redraw — the point is to make "how much" legible at a glance.
- **"today" marker**: a small `Decoration` (`TodayMarkerDecoration`, mirrors `CareEventDecoration`'s coordinate math)
  draws a dashed vertical guideline + a dot at the current day-of-year's position — not a Vico persistent marker API.
- Rendered in two places, both gated behind `FeatureFlagRegistry.SEASONAL_WATERING`: directly under the Settings
  amplitude picker (`showHemisphereCaption = true`, since hemisphere is otherwise inferred with no UI surfacing it
  anywhere else), and in the Plant Detail Water tab's inline settings card next to the "Pin interval" switch
  (`isPinned = plant.pinIntervalToBase`, which grays the curve out at 45% alpha + shows an inline note, since a
  pinned plant's due dates ignore this curve entirely — #578).
- Visualization-only: never touches `CareSchedule.computeStatus()` or `SeasonalWatering.kt`'s actual computation.
- **Y-axis label unit is call-site-controlled (#622).** `SeasonalWateringCurveChart`'s optional
  `baseIntervalDays: Double?` (default `null`) switches the y-axis `valueFormatter` and the
  `seasonal_curve_range`/`seasonal_curve_today` captions between the raw multiplier (`null` — Settings,
  which has no per-plant base interval to anchor days to) and whole days (non-null — Plant Detail,
  `plant.wateringBaseIntervalDays ?: plant.wateringIntervalDays.toDouble()`, the same fallback
  `CareSchedule.effectiveWateringIntervalDaysForDisplay()` uses). The axis's numeric range/step (the
  fixed `0.5×`–`1.5×`/`0.25×` ticks above) is unaffected either way — only the label text converts.
  `seasonalCurveYAxisTicks()`/`seasonalCurveDayTickLabels()` are pure, JVM-tested functions: labels are
  `"Nd"` (`round(baseIntervalDays × multiplier)`), and when two adjacent ticks in axis order round to
  the same day value, the later one's label is blanked (never repeated) — the first tick in axis order
  is never blanked.
