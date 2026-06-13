# ADR-0016: Care event markers rendered as canvas `Decoration` inside the chart

**Status**: accepted

**Date**: 2026-06-12

## Context

Issue #231 asks for non-WATER care events (REPOT, MIST, PRUNE, etc.) to appear as visual markers on the watering history chart so users can correlate care actions with interval changes.

Three implementation approaches were evaluated:

### Option 1: Vico `ColumnCartesianLayer`

Add a second `CartesianLayer` (column bars) to the existing `rememberCartesianChart(...)` call. Each column represents events in a given month.

Problems:
- Both layers share the y-axis by default. Watering intervals are in days (e.g. 7–21 d); a fixed column at y=1 appears as ≈5% of chart height — nearly invisible.
- A separate `endAxis` (right side) for the column layer adds UI noise.
- ADR-0004's NaN constraint must also apply to the column series; months with no events must be omitted, not set to y=0.

### Option 2: Separate Compose row below the chart

Render a `LazyRow` of `SuggestionChip`s below the chart area.

Drawback: chips don't scroll in sync with the Vico chart because Vico's scroll state isn't exposed as a standard Compose `ScrollState`.

### Option 3: Vico `Decoration` with custom canvas drawing (chosen)

Vico 2.0.0 exposes a `Decoration` interface
(`com.patrykandpatrick.vico.core.cartesian.decoration.Decoration`) with
`drawOverLayers(context: CartesianDrawingContext)`. This draws in the same scrollable canvas as the chart layers, so markers automatically scroll with the chart and never appear at wrong positions.

`CartesianDrawingContext` provides:
- `canvas: Canvas` for direct drawing
- `layerBounds: RectF` — the plot area bounds
- `layerDimensions.xSpacing` — pixel spacing between x-values (month indices)
- `scroll: Float` — current scroll offset
- `ranges.minX / ranges.xStep` — x-domain info
- `density: Float` — dp→px conversion

X-position formula (matching `ColumnCartesianLayer`'s internal formula):
```
cx = layerBounds.left + layerDimensions.startPadding
   + (monthIndex - ranges.minX) / ranges.xStep * layerDimensions.xSpacing
   - scroll
```

## Decision

Use Option 3. `CareEventDecoration` implements `Decoration` and draws the care-type icon for each event at the bottom of the chart. Icons are 14 dp, tinted with a per-`CareType` opaque color, and centered at `cy = layerBounds.bottom - iconSize/2 - 2 dp`. When multiple events share the same month, icons stack vertically (bottom-to-top, earliest event at the bottom) using a `stackIndex` multiplier so no two icons overlap.

Care event data (`List<CareEventMarker>`) is written to `ExtraStore` atomically inside the same `runTransaction` block as the line series (preserving the atomicity invariant from ADR-0004). The decoration reads it back via `context.model.extraStore.getOrNull(CareMarkersKey)`.

`rememberCartesianChart` accepts `decorations = listOf(careEventDecoration)`.

### Icon rendering

`CareType.icon()` returns a Compose `ImageVector`; Vico's `drawOverLayers` provides an Android `Canvas`. The bridge is `rememberCareIconBitmaps()`: a private `@Composable` that calls `rememberVectorPainter(careType.icon())` for each care type at composable scope, then rasterizes each painter to an `android.graphics.Bitmap` via `CanvasDrawScope.draw()` inside a `remember(iconSizePx)` block. The resulting `Map<CareType, Bitmap>` is passed to `CareEventDecoration` at construction time. Bitmaps are recreated only when screen density changes.

### Month-index alignment

`computeCareEventMarkers` accepts an `effectiveStartMs` parameter (defaulting to `rangeStartMs`) and anchors `monthBase` to that value. `WateringHistoryChart` computes `effectiveStartMs = computeEffectiveStartMs(intervals, rangeStartMs)` — the same origin used by `ChartContent`'s monthly-points loop — so marker x-positions always align with the correct chart column even when the predecessor-interval optimisation pulls the chart's start month earlier than `rangeStartMs`.

## Consequences

- Care event markers are rendered inside the chart and scroll in sync with the watering interval line.
- Markers do not interfere with the y-axis scale or the line layer.
- The `LaunchedEffect` key is expanded to include `careMarkers`, so adding a new care log triggers a transaction re-run that updates ExtraStore atomically.
- Multiple events in the same month stack vertically; all icons remain individually visible regardless of how many events share a month.
- `CareEventDecoration` receives pre-rasterized `Bitmap` objects; no `Paint` objects are allocated in `drawOverLayers`, keeping per-frame overhead minimal.
