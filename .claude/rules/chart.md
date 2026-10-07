---
description: Vico watering-history chart internals and care-event markers
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/ui/components/*Chart*.kt"
---

# Vico chart rules (technical ADR-0004)

## Watering-history chart (`WateringHistoryChart.kt`)
- **Range and states:** range chips (1M/3M/6M/12M/All), not unified zoom (technical ADR-0004). Empty state below 2 logs; a single interval renders as a circle. `now` is keyed on `wateringLogs`, so a new watering shows immediately.
- **Data:** `computeWateringIntervals()` buckets by calendar month and anchors on the predecessor, so infrequent waterers still get in-window points. Each `daysSincePrevious` is `.coerceAtLeast(1f)`, which keeps "Average interval" ≥ 1 (#446).
- **One point per interval, not monthly averages:** a fractional `monthIndex` uses the same formula as `computeWaterEventMarkers`, so each water-drop icon sits on the line. There is no Vico `PointProvider`. `HorizontalAxis.ItemPlacer.aligned(1)` keeps ticks on whole months, and `rangeProvider` maxX = `totalMonths - 0.001` (#362/#366).
- **The line** is a Catmull-Rom spline (`catmullRomSegments()` → `Path.cubicTo`) drawn on the canvas; Vico's own line is transparent.
- **Care-event markers** (`CareEventDecoration`, Vico `Decoration`):
  - Per-type icons sit at the bottom with day precision; same-day events stack, and icons within 14 dp cluster (`clusterMarkersByCx`).
  - `computeCareEventMarkers()` **explicitly** excludes WATER (its own series) and CHECK. Don't rely on a missing icon bitmap: an undrawn marker would still take a cluster slot. MIST markers stay, for history.
  - Tap → `EventMarkerDialog`, hit-testing `drawnMarkers` within 28 dp (#363).
- **Y axis:** `VerticalAxis.ItemPlacer.step { computeYAxisStep(yMax) }` gives whole-number steps and ≲ 6 ticks; Vico's default made duplicate "0d" labels. `dayFormatter` uses `roundToInt()` (#446).
- **Sync and scrolling:** keep labels and data atomic via `ExtraStore` inside the transaction. `initialScroll` is one-shot, so pair it with `autoScroll` + a custom `AutoScrollCondition`.

## Vico gotchas (apply to any chart)
- **Pass `getXStep` explicitly** when the x unit has a fixed meaning (`getXStep = { _, _, _ -> 1.0 }` for months). Vico infers the step as the GCD of x-deltas, which collapses to ~0.0001 on dense, irregular data such as daily points across unequal months.
- **Unscrollable chart → `rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content)`.** The default `Zoom.max(Zoom.fixed(), Zoom.Content)` never shrinks below the base width (~32 dp per x-unit), so content is clipped on narrow screens. `WateringHistoryChart` keeps the default because it scrolls. Guarded by `SeasonalCurveZoomFitTest`.
- **Never return `""` from a `CartesianValueFormatter`:** Vico 2.5.2 throws (an app crash). Control which ticks get labels with a `VerticalAxis.ItemPlacer`, and filter both `getLabelValues` **and** the width/height measurement methods (`getMaxLabelWidth` formats those values too) (#638).
- **Axis label `TextComponent`s are built inside `ProvideVicoTheme { }`** (`rememberAxisLabelComponent()`). Otherwise the color silently falls back to Vico's palette instead of the M3 theme.
- A Vico canvas has no semantics tree, so put layout decisions in pure, JVM-tested functions.

## Seasonal curve preview (`SeasonalWateringCurveChart.kt`, #579)
- **Separate chart, not a scaled-down history chart.** It samples one year with `SeasonalWateringCurveSampler`: 365/366 points on the same fractional month-index scheme (`monthIndexFor()`, shared `fractionalDayOfMonth()` rounded to 4 decimals for Vico's GCD).
- **Y axis is fixed at 0.5×–1.5×** (STRONG's bounds, 0.25× steps) whatever the amplitude, so changing the amplitude changes the curve's height, not the axis.
- **Today marker:** `TodayMarkerDecoration` draws a dashed line + dot (not Vico's marker API).
- **Where it renders:**
  - Settings, under the amplitude picker, with `showHemisphereCaption = true` (the only place the hemisphere is surfaced).
  - Plant Detail's Water tab, with `plantContext = SeasonalCurvePlantContext(isPinned, baseIntervalDays)`. Pinned shows the curve at 45% alpha plus a note (#578).
- **Day labels (#622):**
  - A non-null `baseIntervalDays` (Plant Detail: `wateringBaseIntervalDays ?: wateringIntervalDays`) switches the axis labels and captions to whole days (`"Nd"`); Settings shows the raw multiplier.
  - Duplicate adjacent day labels are withheld: the pure `seasonalCurveDayTickLabels()` decides which ticks keep a label (never the first), `seasonalCurveLabeledTicks()` filters the ticks, and `DayLabelItemPlacer` (delegating to `step(Y_AXIS_STEP)`, matching by `TICK_MATCH_EPSILON`) applies the result. Gridlines are untouched.
- **Responsive month labels (#621):**
  - `BoxWithConstraints` measures the real plot width and feeds the pure `resolveMonthLabelStrategy()`, with an injected `measureTextWidthPx`.
  - It falls through `"MMM"` → shrunk to a 9 sp floor → single letters → alternate months unlabeled.
  - The y-axis width is estimated from its fixed `"0.50×"`–`"1.50×"` format (`estimateYAxisReservedWidthPx`).
- Visualization only; never changes `computeStatus()` or `SeasonalWatering`.
