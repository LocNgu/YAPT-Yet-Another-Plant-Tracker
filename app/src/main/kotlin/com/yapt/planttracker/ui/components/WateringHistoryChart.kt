package com.yapt.planttracker.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.AutoScrollCondition
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.ui.util.icon
import com.yapt.planttracker.ui.util.labelRes
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val DAY_IN_MS = 24L * 60 * 60 * 1000
private const val DATE_FORMAT_MONTH = "MMM"
private const val DATE_FORMAT_MONTH_YEAR = "MMM yy"
private const val DATE_FORMAT_MONTH_DAY_YEAR = "MMM d, yyyy"
private val MonthLabelsKey = ExtraStore.Key<Map<Int, String>>()
private val CareMarkersKey = ExtraStore.Key<List<CareEventMarker>>()
private val WaterPointsKey = ExtraStore.Key<List<WaterDataPoint>>()

internal data class PositionedMarker(val cx: Float, val marker: CareEventMarker)

/**
 * A care icon as actually rendered on the chart canvas, retained after each draw pass so
 * tap gestures can hit-test against it. Coordinates are in the composable's local physical
 * pixels — the same space as Compose's `detectTapGestures` offsets.
 */
internal data class DrawnMarkerInfo(
    val cx: Float,
    val cy: Float,
    val careType: CareType,
    val timestamps: List<Long>,
)

private val careTypeColors = mapOf(
    CareType.WATER to 0xFF1565C0.toInt(),
    CareType.FERTILIZE to 0xFF795548.toInt(),
    CareType.PRUNE to 0xFF388E3C.toInt(),
    CareType.MIST to 0xFF6B8F71.toInt(),
    CareType.REPOT to 0xFFFF8F00.toInt(),
    CareType.NOTE to 0xFF9E9E9E.toInt(),
    CareType.PHOTO to 0xFF7B1FA2.toInt(),
)

internal enum class TimeRange(val labelRes: Int, val daysBack: Int) {
    ONE_MONTH(R.string.time_range_1m, 30),
    THREE_MONTHS(R.string.time_range_3m, 90),
    SIX_MONTHS(R.string.time_range_6m, 180),
    TWELVE_MONTHS(R.string.time_range_12m, 365),
    ALL_TIME(R.string.time_range_all, Int.MAX_VALUE)
}

internal data class WateringInterval(
    val timestamp: Long,
    val daysSincePrevious: Float
)

internal data class CareEventMarker(
    val monthIndex: Float,
    val careType: CareType,
    val timestamp: Long
)

internal data class WaterDataPoint(
    val monthIndex: Float,
    val daysSincePrevious: Float,
    val timestamp: Long,
)

/**
 * Maps a y-value (days since previous watering) to a canvas y-coordinate within the
 * layer bounds, matching how Vico's line layer positions points. Returns the vertical
 * centre for a degenerate range (all points equal), mirroring Vico's own behaviour.
 */
internal fun markerCy(
    daysSincePrevious: Float,
    yMin: Float,
    yMax: Float,
    top: Float,
    bottom: Float
): Float =
    if (yMax > yMin) bottom - ((daysSincePrevious - yMin) / (yMax - yMin)) * (bottom - top)
    else (top + bottom) / 2f

private class CareEventDecoration(
    private val iconBitmaps: Map<CareType, Bitmap>,
    private val lineColor: Int,
) : Decoration {
    private val linePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    // Reused across draw passes (main-thread only) to avoid per-frame allocation, like linePaint.
    private val linePath = android.graphics.Path()

    /**
     * Positions of the icons drawn in the most recent [drawOverLayers] pass, used for tap
     * hit-testing. A plain `var` (not `MutableState`) is intentional: Vico draws on the main
     * thread and `pointerInput` callbacks also run on the main thread, so reads see the latest
     * value, and we avoid triggering recomposition on every frame.
     */
    var drawnMarkers: List<DrawnMarkerInfo> = emptyList()

    override fun drawOverLayers(context: CartesianDrawingContext) {
        val markers = context.model.extraStore.getOrNull(CareMarkersKey) ?: emptyList()
        val waterPoints = context.model.extraStore.getOrNull(WaterPointsKey) ?: emptyList()
        if (markers.isEmpty() && waterPoints.isEmpty()) {
            drawnMarkers = emptyList()
            return
        }
        val newDrawn = mutableListOf<DrawnMarkerInfo>()
        with(context) {
            val iconSize = density * 14f
            val gap = density * 2f

            // Water icons: only drawn when there is line data to position them against.
            // getYRange(null) requires a non-empty line series; guard to prevent NPE when
            // the plant has care events but no waterings in the selected range.
            val yRange = if (waterPoints.isNotEmpty()) ranges.getYRange(null) else null
            if (waterPoints.isNotEmpty() && yRange != null) {
                val yMin = yRange.minY.toFloat()
                val yMax = yRange.maxY.toFloat()

                val sorted = waterPoints.sortedBy { it.monthIndex }

                // Compute canvas coords for every point — off-screen points included so
                // the polyline extends continuously to the chart edge during scrolling.
                val coords = sorted.map { wp ->
                    val cx = layerBounds.left + layerDimensions.startPadding +
                        ((wp.monthIndex - ranges.minX.toFloat()) / ranges.xStep.toFloat()) *
                        layerDimensions.xSpacing - scroll
                    val cy = markerCy(wp.daysSincePrevious, yMin, yMax, layerBounds.top, layerBounds.bottom)
                    cx to cy
                }

                // Draw connecting curve as a smooth cubic (Catmull-Rom) spline through every
                // point, instead of straight zig-zag segments. A single point draws no line.
                linePaint.color = lineColor
                linePaint.strokeWidth = density * 2f
                val segments = catmullRomSegments(coords)
                if (segments.isNotEmpty()) {
                    linePath.rewind()
                    val (startX, startY) = coords.first()
                    linePath.moveTo(startX, startY)
                    segments.forEach { s ->
                        linePath.cubicTo(s.c1x, s.c1y, s.c2x, s.c2y, s.endX, s.endY)
                    }
                    canvas.drawPath(linePath, linePaint)
                }

                // Draw water-drop icons on top of line (only on-screen points)
                sorted.zip(coords).forEach { (wp, pair) ->
                    val (cx, cy) = pair
                    if (cx < layerBounds.left || cx > layerBounds.right) return@forEach
                    val bm = iconBitmaps[CareType.WATER] ?: return@forEach
                    canvas.drawBitmap(bm, cx - bm.width / 2f, cy - bm.height / 2f, null)
                    newDrawn.add(DrawnMarkerInfo(cx, cy, CareType.WATER, listOf(wp.timestamp)))
                }
            }

            val markerPositions = markers.map { marker ->
                val cx = layerBounds.left + layerDimensions.startPadding +
                    ((marker.monthIndex - ranges.minX.toFloat()) / ranges.xStep.toFloat()) *
                    layerDimensions.xSpacing - scroll
                PositionedMarker(cx, marker)
            }.filter { it.cx >= layerBounds.left && it.cx <= layerBounds.right }
            clusterMarkersByCx(markerPositions, iconSize).forEach { cluster ->
                val clusterCx = cluster.map { it.cx }.average().toFloat()
                cluster.map { it.marker }.sortedBy { it.timestamp }
                    .forEachIndexed { stackIndex, marker ->
                        val cy = layerBounds.bottom - iconSize / 2f - gap -
                            stackIndex * (iconSize + gap)
                        val bm = iconBitmaps[marker.careType] ?: return@forEachIndexed
                        canvas.drawBitmap(bm, clusterCx - bm.width / 2f, cy - bm.height / 2f, null)
                        newDrawn.add(
                            DrawnMarkerInfo(clusterCx, cy, marker.careType, listOf(marker.timestamp))
                        )
                    }
            }
        }
        drawnMarkers = newDrawn
    }
}

@Composable
private fun rememberCareIconBitmaps(): Map<CareType, Bitmap> {
    val density = LocalDensity.current
    val iconSizePx = with(density) { 14.dp.roundToPx() }
    val painters = careTypeColors.keys.associateWith { rememberVectorPainter(it.icon()) }
    return remember(iconSizePx) {
        val drawScope = CanvasDrawScope()
        painters.entries.associate { (careType, painter) ->
            val color = careTypeColors.getValue(careType)
            val bm = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
            drawScope.draw(
                density = density,
                layoutDirection = LayoutDirection.Ltr,
                canvas = Canvas(android.graphics.Canvas(bm)),
                size = Size(iconSizePx.toFloat(), iconSizePx.toFloat()),
            ) {
                with(painter) {
                    draw(
                        size = Size(iconSizePx.toFloat(), iconSizePx.toFloat()),
                        colorFilter = ColorFilter.tint(Color(color)),
                    )
                }
            }
            careType to bm
        }
    }
}

@Composable
internal fun WateringHistoryChart(
    careLogs: List<CareLog>,
    selectedRange: TimeRange = TimeRange.TWELVE_MONTHS,
    onRangeSelected: (TimeRange) -> Unit
) {
    val wateringLogs = careLogs.filter { it.careType == CareType.WATER }
        .sortedBy { it.loggedAt }

    val now = remember(wateringLogs) { System.currentTimeMillis() }
    val rangeStartMs = when (selectedRange) {
        TimeRange.ALL_TIME -> wateringLogs.minByOrNull { it.loggedAt }?.loggedAt ?: now
        else -> now - (selectedRange.daysBack.toLong() * DAY_IN_MS)
    }

    val intervals = computeWateringIntervals(wateringLogs, rangeStartMs, now)
    val effectiveStartMs = computeEffectiveStartMs(intervals, rangeStartMs)
    val careMarkers = computeCareEventMarkers(careLogs, rangeStartMs, now, effectiveStartMs)
    // Use effectiveStartMs (not rangeStartMs) as the filter floor so pre-range anchor
    // intervals are included in the line data — the old monthly-bucket code included them.
    val waterMarkers = computeWaterEventMarkers(intervals, effectiveStartMs, now, effectiveStartMs)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.watering_history),
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimeRange.values().forEach { range ->
                FilterChip(
                    selected = range == selectedRange,
                    onClick = { onRangeSelected(range) },
                    label = { Text(stringResource(range.labelRes)) }
                )
            }
        }

        if (intervals.isEmpty()) {
            Text(
                text = stringResource(R.string.insufficient_watering_logs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 32.dp)
            )
        } else {
            ChartContent(intervals, effectiveStartMs, now, careMarkers, waterMarkers)
            ChartLegend(intervals)
        }
    }
}

@Composable
private fun ChartContent(
    intervals: List<WateringInterval>,
    effectiveStartMs: Long,
    now: Long,
    careMarkers: List<CareEventMarker>,
    waterMarkers: List<WaterDataPoint>,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val iconBitmaps = rememberCareIconBitmaps()
    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    val careEventDecoration = remember(iconBitmaps, primaryColor) {
        CareEventDecoration(iconBitmaps, primaryColor)
    }
    var selectedMarker by remember { mutableStateOf<DrawnMarkerInfo?>(null) }

    val zone = ZoneId.systemDefault()

    val (monthlyPoints, monthLabels) = remember(intervals, effectiveStartMs, now) {
        val points = mutableListOf<Pair<Float, Float>>()
        val indexToZdt = mutableListOf<ZonedDateTime>()
        val monthBase = ZonedDateTime.ofInstant(Instant.ofEpochMilli(effectiveStartMs), zone)
            .withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
        val nowZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        var monthIndex = 0
        var monthStart = monthBase
        while (!monthStart.isAfter(nowZdt)) {
            val monthStartMs = monthStart.toInstant().toEpochMilli()
            val monthEndMs = monthStart.plusMonths(1).toInstant().toEpochMilli()
            val monthIntervals = intervals.filter {
                it.timestamp >= monthStartMs && it.timestamp < monthEndMs
            }
            if (monthIntervals.isNotEmpty()) {
                points.add(monthIndex.toFloat() to monthIntervals.map { it.daysSincePrevious }.average().toFloat())
            }
            indexToZdt.add(monthStart)
            monthIndex++
            monthStart = monthBase.plusMonths(monthIndex.toLong())
        }

        // If any short month name repeats (e.g. "May" twice in a 12-month range that
        // crosses a year boundary), fall back to "MMM yy" so each label is unique.
        val fmtShort = DateTimeFormatter.ofPattern(DATE_FORMAT_MONTH).withZone(zone)
        val shortNames = indexToZdt.map { fmtShort.format(it) }
        val fmt = if (shortNames.toSet().size < shortNames.size)
            DateTimeFormatter.ofPattern(DATE_FORMAT_MONTH_YEAR).withZone(zone)
        else fmtShort
        val labels = indexToZdt.mapIndexed { idx, zdt -> idx to fmt.format(zdt) }.toMap()

        points to labels
    }

    // Key on waterMarkers, careMarkers, effectiveStartMs, AND now so the transaction
    // re-runs when any of these change. All data written atomically to prevent mismatched
    // label/data/marker snapshots (ADR-0004).
    LaunchedEffect(intervals, careMarkers, effectiveStartMs, now, waterMarkers) {
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = monthlyPoints.map { it.first },   // integers — no Vico precision issue
                    y = monthlyPoints.map { it.second }
                )
            }
            extras { store ->
                store[MonthLabelsKey] = monthLabels
                store[CareMarkersKey] = careMarkers
                store[WaterPointsKey] = waterMarkers
            }
        }
    }

    val dateFormatter = remember {
        CartesianValueFormatter { context, x, _ ->
            context.model.extraStore.getOrNull(MonthLabelsKey)?.get(x.roundToInt()) ?: " "
        }
    }

    val dayFormatter = remember {
        CartesianValueFormatter { _, y, _ ->
            "${y.roundToInt()}d"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.days_between_waterings),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val totalMonths = monthLabels.size
        val yMax = remember(waterMarkers) {
            if (waterMarkers.isNotEmpty()) waterMarkers.maxOf { it.daysSincePrevious }.toDouble() else 1.0
        }
        // Whole-number step derived from yMax so ticks land on distinct integer days and
        // the axis never shows more than ~6 labels, regardless of the data's range.
        val yStep = remember(yMax) { computeYAxisStep(yMax) }
        val rangeProvider = remember(totalMonths, yMax) {
            CartesianLayerRangeProvider.fixed(
                minX = 0.0,
                maxX = totalMonths.toDouble() - 0.001,
                minY = 0.0,
                maxY = yMax,
            )
        }

        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(
                        // Vico's monthly-bucket line is invisible; the decoration draws the
                        // per-event smooth cubic-spline curve and water-drop icons directly on
                        // the canvas.
                        LineCartesianLayer.rememberLine(
                            fill = LineCartesianLayer.LineFill.single(fill(Color.Transparent))
                        )
                    ),
                    rangeProvider = rangeProvider,
                ),
                startAxis = VerticalAxis.rememberStart(
                    valueFormatter = dayFormatter,
                    // Force a fixed whole-day step instead of Vico's default automatic
                    // step (which can land on fractional values like 0.5, 1.5 and, after
                    // truncation/rounding, produce duplicate "Nd" labels).
                    itemPlacer = remember(yStep) {
                        VerticalAxis.ItemPlacer.step(step = { yStep.toDouble() })
                    },
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = dateFormatter,
                    // Place ticks at integer month boundaries only; without this Vico
                    // would emit one tick per fractional event position, cluttering the axis.
                    itemPlacer = remember { HorizontalAxis.ItemPlacer.aligned(spacing = { 1 }) },
                ),
                decorations = listOf(careEventDecoration),
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                // Key on the stable decoration so the lambda is recreated only when icon
                // bitmaps change; it always reads the latest drawnMarkers via field access.
                .pointerInput(careEventDecoration) {
                    detectTapGestures { tapOffset ->
                        val thresholdPx = 28.dp.toPx()
                        val hit = careEventDecoration.drawnMarkers.minByOrNull { dm ->
                            val dx = dm.cx - tapOffset.x
                            val dy = dm.cy - tapOffset.y
                            dx * dx + dy * dy
                        }
                        if (hit != null) {
                            val dx = hit.cx - tapOffset.x
                            val dy = hit.cy - tapOffset.y
                            if (dx * dx + dy * dy <= thresholdPx * thresholdPx) {
                                selectedMarker = hit
                            }
                        }
                    }
                },
            scrollState = rememberVicoScrollState(
                scrollEnabled = true,
                initialScroll = Scroll.Absolute.End,
                autoScroll = Scroll.Absolute.End,
                // Fire only when the data's x-extent changes — which happens on
                // a time-range switch or a new watering at the right edge — so
                // unrelated CareLog emissions (fertilize, prune, photo) don't
                // yank the chart away while the user is scrolling history.
                autoScrollCondition = AutoScrollCondition { old, new ->
                    old?.models?.firstOrNull()?.maxX != new?.models?.firstOrNull()?.maxX
                },
            ),
            zoomState = rememberVicoZoomState(zoomEnabled = false),
        )
    }


    selectedMarker?.let { marker ->
        EventMarkerDialog(marker = marker, onDismiss = { selectedMarker = null })
    }
}

@Composable
private fun EventMarkerDialog(marker: DrawnMarkerInfo, onDismiss: () -> Unit) {
    val datesText = remember(marker.timestamps) {
        val fmt = DateTimeFormatter.ofPattern(DATE_FORMAT_MONTH_DAY_YEAR).withZone(ZoneId.systemDefault())
        marker.timestamps.sorted().joinToString("\n") { fmt.format(Instant.ofEpochMilli(it)) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(marker.careType.labelRes())) },
        text = { Text(datesText) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    )
}

@Composable
private fun ChartLegend(intervals: List<WateringInterval>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (intervals.isNotEmpty()) {
            val lastInterval = intervals.last()
            val dateFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT_MONTH_DAY_YEAR)
                .withZone(ZoneId.systemDefault())
            val dateStr = dateFormatter.format(Instant.ofEpochMilli(lastInterval.timestamp))

            Text(
                text = stringResource(R.string.last_watering, dateStr),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val avgInterval = intervals.map { it.daysSincePrevious }.average()
            Text(
                text = stringResource(R.string.average_interval, avgInterval),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun computeWateringIntervals(
    wateringLogs: List<CareLog>,
    rangeStartMs: Long,
    now: Long
): List<WateringInterval> {
    val inRange = wateringLogs.filter { it.loggedAt >= rangeStartMs && it.loggedAt <= now }
    val beforeRange = wateringLogs
        .filter { it.loggedAt < rangeStartMs }
        .sortedByDescending { it.loggedAt }

    // When there are no in-range logs, fall back to the last 2 pre-range waterings so
    // plants watered less than once per month still get one interval point.
    val anchorsNeeded = if (inRange.isEmpty()) 2 else 1
    val anchors = beforeRange.take(anchorsNeeded).reversed()

    val workingList = anchors + inRange
    if (workingList.size < 2) return emptyList()

    val intervals = mutableListOf<WateringInterval>()
    for (i in 1 until workingList.size) {
        val prev = workingList[i - 1]
        val curr = workingList[i]
        // Floor at 1 day: this is the displayed tracking interval, so a genuine sub-day
        // watering (two waterings <24h apart) still plots as a meaningful 1-day point
        // instead of a near-zero fraction that skews the chart and the average.
        val daysDiff = ((curr.loggedAt - prev.loggedAt) / (24 * 60 * 60 * 1000).toFloat())
            .coerceAtLeast(1f)
        intervals.add(WateringInterval(curr.loggedAt, daysDiff))
    }
    return intervals
}

internal fun computeEffectiveStartMs(intervals: List<WateringInterval>, rangeStartMs: Long): Long =
    if (intervals.isNotEmpty()) minOf(rangeStartMs, intervals.minOf { it.timestamp })
    else rangeStartMs

/**
 * Whole-number y-axis tick step for the watering interval chart, derived from the data's
 * maximum value so the axis shows roughly 5-6 ticks regardless of range. Always at least 1,
 * so ticks never collapse to a single repeated label.
 */
internal fun computeYAxisStep(yMax: Double): Int = maxOf(1, ceil(yMax / 5.0).toInt())

internal fun computeCareEventMarkers(
    careLogs: List<CareLog>,
    rangeStartMs: Long,
    now: Long,
    effectiveStartMs: Long = rangeStartMs,
): List<CareEventMarker> {
    val inRange = careLogs.filter {
        it.careType != CareType.WATER && it.loggedAt >= rangeStartMs && it.loggedAt <= now
    }
    if (inRange.isEmpty()) return emptyList()

    val zone = ZoneId.systemDefault()
    val monthBase = ZonedDateTime.ofInstant(Instant.ofEpochMilli(effectiveStartMs), zone)
        .withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)

    return inRange.map { log ->
        val logZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(log.loggedAt), zone)
        val completedMonths = ChronoUnit.MONTHS.between(monthBase, logZdt).toInt()
        val monthStartZdt = monthBase.plusMonths(completedMonths.toLong())
        val daysInMonth = monthStartZdt.toLocalDate().lengthOfMonth()
        CareEventMarker(
            monthIndex = completedMonths + (logZdt.dayOfMonth - 1).toFloat() / daysInMonth,
            careType = log.careType,
            timestamp = log.loggedAt
        )
    }.sortedBy { it.timestamp }
}

internal fun computeWaterEventMarkers(
    intervals: List<WateringInterval>,
    rangeStartMs: Long,
    now: Long,
    effectiveStartMs: Long = rangeStartMs,
): List<WaterDataPoint> {
    val inRange = intervals.filter { it.timestamp >= rangeStartMs && it.timestamp <= now }
    if (inRange.isEmpty()) return emptyList()

    val zone = ZoneId.systemDefault()
    val monthBase = ZonedDateTime.ofInstant(Instant.ofEpochMilli(effectiveStartMs), zone)
        .withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)

    return inRange.map { interval ->
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(interval.timestamp), zone)
        val completedMonths = ChronoUnit.MONTHS.between(monthBase, zdt).toInt()
        val monthStartZdt = monthBase.plusMonths(completedMonths.toLong())
        val daysInMonth = monthStartZdt.toLocalDate().lengthOfMonth()
        WaterDataPoint(
            // Round to 4 decimal places: Vico 2.0.0 throws IllegalArgumentException if x
            // values have more than 4 decimal places (it uses GCD precision internally).
            monthIndex = completedMonths + ((zdt.dayOfMonth - 1).toFloat() / daysInMonth * 10000).roundToInt() / 10000f,
            daysSincePrevious = interval.daysSincePrevious,
            timestamp = interval.timestamp,
        )
    }
}

internal fun clusterMarkersByCx(
    markerPositions: List<PositionedMarker>,
    iconSize: Float,
): List<List<PositionedMarker>> {
    if (markerPositions.isEmpty()) return emptyList()
    val sorted = markerPositions.sortedBy { it.cx }
    val clusters = mutableListOf<MutableList<PositionedMarker>>()
    var current = mutableListOf(sorted[0])
    for (i in 1 until sorted.size) {
        val item = sorted[i]
        if (item.cx - current.last().cx > iconSize) {
            clusters.add(current)
            current = mutableListOf(item)
        } else {
            current.add(item)
        }
    }
    clusters.add(current)
    return clusters
}

internal data class CubicSegment(
    val c1x: Float,
    val c1y: Float,
    val c2x: Float,
    val c2y: Float,
    val endX: Float,
    val endY: Float,
)

/**
 * Converts a polyline of `(x, y)` canvas coordinates into a sequence of cubic Bézier segments
 * following a Catmull-Rom spline with clamped endpoints, producing a smooth curve that passes
 * through every input point. Vico's own line is invisible here (the decoration hand-draws the
 * line so water-drop icons align on it), so smoothing is applied to these points directly rather
 * than via a Vico cubic connector.
 *
 * Returns an empty list for fewer than two points (nothing to connect). Collinear input yields
 * control points that lie on the line, so straight runs stay straight.
 *
 * Each segment's control-point y is clamped to the `[min, max]` of that segment's two endpoint
 * y-values. A cubic Bézier is contained in the convex hull of its control points, so this keeps
 * the curve's y within the band of consecutive data points — preventing the classic Catmull-Rom
 * overshoot from bulging the line above the chart's declared `maxY` near an asymmetric peak. Only
 * y is clamped; x is left untouched to preserve horizontal smoothness (x is already monotonic).
 */
internal fun catmullRomSegments(points: List<Pair<Float, Float>>): List<CubicSegment> {
    if (points.size < 2) return emptyList()
    val last = points.size - 1
    val segments = ArrayList<CubicSegment>(last)
    for (i in 0 until last) {
        val p0 = points[if (i == 0) 0 else i - 1]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[if (i + 2 > last) last else i + 2]
        val loY = minOf(p1.second, p2.second)
        val hiY = maxOf(p1.second, p2.second)
        segments.add(
            CubicSegment(
                c1x = p1.first + (p2.first - p0.first) / 6f,
                c1y = (p1.second + (p2.second - p0.second) / 6f).coerceIn(loY, hiY),
                c2x = p2.first - (p3.first - p1.first) / 6f,
                c2y = (p2.second - (p3.second - p1.second) / 6f).coerceIn(loY, hiY),
                endX = p2.first,
                endY = p2.second,
            )
        )
    }
    return segments
}
