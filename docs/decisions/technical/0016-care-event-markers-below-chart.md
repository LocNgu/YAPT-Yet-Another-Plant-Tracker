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

Use Option 3. `CareEventDecoration` implements `Decoration` and draws small filled circles (radius 4 dp, one per care event) at the bottom of the chart (`cy = layerBounds.bottom - radius - 2 dp`). Each `CareType` has a distinct opaque color. Care event data (`List<CareEventMarker>`) is written to `ExtraStore` atomically inside the same `runTransaction` block as the line series (preserving the atomicity invariant from ADR-0004). The decoration reads it back via `context.model.extraStore.getOrNull(CareMarkersKey)`.

`rememberCartesianChart` accepts `decorations = listOf(careEventDecoration)`.

## Consequences

- Care event markers are rendered inside the chart and scroll in sync with the watering interval line.
- Markers do not interfere with the y-axis scale or the line layer.
- The `LaunchedEffect` key is expanded to include `careMarkers`, so adding a new care log triggers a transaction re-run that updates ExtraStore atomically.
- Multiple events in the same month draw at the same x-coordinate (circles overlap); their distinct colors remain perceptible through color mixing.
- `CareEventDecoration` pre-allocates one `Paint` per care type at construction time, avoiding per-frame allocations in `drawOverLayers`.
