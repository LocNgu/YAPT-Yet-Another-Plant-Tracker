package com.yapt.planttracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.component.TextComponent
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalCurvePoint
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.SeasonalWateringCurveSampler
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val Y_AXIS_HALF_RANGE = 0.5
private const val Y_AXIS_STEP = 0.25
private val MonthLabelsKey = ExtraStore.Key<Map<Int, String>>()
private val TodayPointKey = ExtraStore.Key<TodayCurvePoint>()

internal data class TodayCurvePoint(val x: Float, val y: Float)

/**
 * Fractional month-index x-coordinate for [date] (Jan 1 = 0.0, Dec 31 ≈ 11.97), matching
 * `WateringHistoryChart.kt`'s monthly-tick convention so the same `HorizontalAxis.ItemPlacer
 * .aligned(spacing = { 1 })` places ticks exactly at month boundaries. Rounded to 4 decimal places
 * — Vico 2.0.0 throws `IllegalArgumentException` on higher-precision x values (its GCD-based
 * internal precision handling).
 */
internal fun monthIndexFor(date: LocalDate): Float =
    (date.monthValue - 1) + fractionalDayOfMonth(date.dayOfMonth, date.lengthOfMonth())

/** Draws a dashed vertical guideline + a highlighted dot at "today"'s position on the curve. */
private class TodayMarkerDecoration(
    private val dotColor: Int,
    private val guidelineColor: Int,
) : Decoration {
    private val guidelinePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
    }
    private val dotPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
    }

    override fun drawOverLayers(context: CartesianDrawingContext) {
        val today = context.model.extraStore.getOrNull(TodayPointKey) ?: return
        val yRange = context.ranges.getYRange(null) ?: return
        with(context) {
            val cx = layerBounds.left + layerDimensions.startPadding +
                ((today.x - ranges.minX.toFloat()) / ranges.xStep.toFloat()) * layerDimensions.xSpacing - scroll
            if (cx in layerBounds.left..layerBounds.right) {
                val cy = markerCy(
                    today.y,
                    yRange.minY.toFloat(),
                    yRange.maxY.toFloat(),
                    layerBounds.top,
                    layerBounds.bottom,
                )

                guidelinePaint.color = guidelineColor
                guidelinePaint.strokeWidth = density * 1.5f
                guidelinePaint.pathEffect =
                    android.graphics.DashPathEffect(floatArrayOf(density * 4f, density * 4f), 0f)
                canvas.drawLine(cx, layerBounds.top, cx, layerBounds.bottom, guidelinePaint)

                dotPaint.color = dotColor
                canvas.drawCircle(cx, cy, density * 4f, dotPaint)
            }
        }
    }
}

/**
 * Compact preview chart of [SeasonalWatering.season]'s yearly curve (#579, follow-up to
 * #569/product ADR-0026) — shown directly under the Settings amplitude picker, and in the Plant
 * Detail Water tab's inline settings card next to the "Pin interval" switch. Built from the same
 * Vico primitives as `WateringHistoryChart.kt` (own file per `.claude/rules/chart.md`), not a
 * scaled-down reuse of it.
 *
 * The y-axis is the raw multiplier (fixed 0.5×–1.5×, spanning [SeasonalAmplitude.STRONG]'s bounds
 * regardless of the currently selected [amplitude]) so switching amplitudes visibly changes the
 * curve's *height* within a constant frame, rather than rescaling the axis each time. [isPinned]
 * (Plant Detail only) grays the curve out and adds an inline note — the plant's due dates ignore
 * this curve entirely while pinned (#578).
 */
@Composable
internal fun SeasonalWateringCurveChart(
    amplitude: Double,
    hemisphere: Hemisphere,
    modifier: Modifier = Modifier,
    isPinned: Boolean = false,
    showHemisphereCaption: Boolean = false,
) {
    val today = remember { LocalDate.now() }
    val points = remember(amplitude, hemisphere, today.year) {
        SeasonalWateringCurveSampler.sample(amplitude, hemisphere, today.year)
    }
    val todayMultiplier = remember(amplitude, hemisphere, today) {
        SeasonalWatering.season(today, amplitude, hemisphere)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.seasonal_curve_title),
            style = MaterialTheme.typography.labelLarge
        )

        SeasonalCurveChartBody(
            points = points,
            today = today,
            todayMultiplier = todayMultiplier,
            isPinned = isPinned,
        )

        if (showHemisphereCaption) {
            HemisphereCaption(hemisphere)
        }
        if (isPinned) {
            Text(
                text = stringResource(R.string.seasonal_curve_pinned_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun SeasonalCurveChartBody(
    points: List<SeasonalCurvePoint>,
    today: LocalDate,
    todayMultiplier: Double,
    isPinned: Boolean,
) {
    val minMultiplier = remember(points) { points.minOf { it.multiplier } }
    val maxMultiplier = remember(points) { points.maxOf { it.multiplier } }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.alpha(if (isPinned) 0.45f else 1f)) {
            SeasonalCurveVicoChart(points, today, todayMultiplier)
        }

        Text(
            text = stringResource(R.string.seasonal_curve_range, minMultiplier, maxMultiplier),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = stringResource(R.string.seasonal_curve_today, todayMultiplier),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun HemisphereCaption(hemisphere: Hemisphere) {
    val hemisphereLabel = stringResource(
        if (hemisphere == Hemisphere.SOUTHERN) {
            R.string.seasonal_curve_hemisphere_southern
        } else {
            R.string.seasonal_curve_hemisphere_northern
        }
    )
    val peakMonth = remember(hemisphere) {
        val fmt = DateTimeFormatter.ofPattern("MMMM")
        fmt.format(LocalDate.ofYearDay(2001, SeasonalWatering.peakDayOfYear(hemisphere)))
    }
    Text(
        text = stringResource(R.string.seasonal_curve_hemisphere_caption, hemisphereLabel, peakMonth),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun SeasonalCurveVicoChart(
    points: List<SeasonalCurvePoint>,
    today: LocalDate,
    todayMultiplier: Double,
) {
    val baseMonthLabels = remember {
        val fmt = DateTimeFormatter.ofPattern("MMM")
        (0 until MONTHS_IN_YEAR).map { fmt.format(LocalDate.of(2001, it + 1, 1)) }
    }

    val curveColor = MaterialTheme.colorScheme.primary
    val todayDotColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val todayGuidelineColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f).toArgb()
    val todayMarkerDecoration = remember(todayDotColor, todayGuidelineColor) {
        TodayMarkerDecoration(todayDotColor, todayGuidelineColor)
    }

    val modelProducer = remember { CartesianChartModelProducer() }
    val density = LocalDensity.current

    // BoxWithConstraints measures the actual width given at runtime rather than testing against a
    // hardcoded breakpoint (#621) — the month-label format/size continuously adapts to it.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availableWidthPx = with(density) { maxWidth.toPx() }
        SeasonalCurveChartHost(
            curveSeries = SeasonalCurveSeries(points, today, todayMultiplier),
            visuals = SeasonalCurveVisuals(curveColor, todayMarkerDecoration),
            layout = SeasonalCurveLayout(baseMonthLabels, availableWidthPx, density),
            modelProducer = modelProducer,
        )
    }
}

private data class SeasonalCurveSeries(
    val points: List<SeasonalCurvePoint>,
    val today: LocalDate,
    val todayMultiplier: Double,
)

private data class SeasonalCurveVisuals(
    val curveColor: Color,
    val todayMarkerDecoration: Decoration,
)

private data class SeasonalCurveLayout(
    val baseMonthLabels: List<String>,
    val availableWidthPx: Float,
    val density: Density,
)

@Composable
private fun SeasonalCurveChartHost(
    curveSeries: SeasonalCurveSeries,
    visuals: SeasonalCurveVisuals,
    layout: SeasonalCurveLayout,
    modelProducer: CartesianChartModelProducer,
) {
    val monthLabelStrategy = remember(layout.availableWidthPx, layout.baseMonthLabels) {
        val plotWidthPx = (layout.availableWidthPx - estimateYAxisReservedWidthPx(layout.density)).coerceAtLeast(0f)
        resolveMonthLabelStrategy(
            availableWidthPx = plotWidthPx,
            monthLabels = layout.baseMonthLabels,
            measureTextWidthPx = { text, fontSizeSp -> measureLabelWidthPx(text, fontSizeSp, layout.density) },
        )
    }
    val monthLabels = remember(layout.baseMonthLabels, monthLabelStrategy) {
        buildMonthLabelMap(layout.baseMonthLabels, monthLabelStrategy)
    }

    LaunchedEffect(curveSeries.points, monthLabels, curveSeries.todayMultiplier) {
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = curveSeries.points.map { monthIndexFor(it.date) },
                    y = curveSeries.points.map { it.multiplier.toFloat() }
                )
            }
            extras { store ->
                store[MonthLabelsKey] = monthLabels
                store[TodayPointKey] =
                    TodayCurvePoint(monthIndexFor(curveSeries.today), curveSeries.todayMultiplier.toFloat())
            }
        }
    }

    val monthFormatter = remember {
        CartesianValueFormatter { context, x, _ ->
            context.model.extraStore.getOrNull(MonthLabelsKey)?.get(x.roundToInt()) ?: " "
        }
    }
    val multiplierFormatter = remember {
        CartesianValueFormatter { _, y, _ -> String.format(Locale.getDefault(), "%.2f×", y) }
    }

    ProvideVicoTheme(rememberM3VicoTheme()) {
        // rememberAxisLabelComponent() defaults its color to vicoTheme.textColor, a
        // CompositionLocal only set inside this ProvideVicoTheme scope — must be created here,
        // not above, or the month labels would fall back to Vico's own Light/Dark palette instead
        // of the M3 theme's.
        val monthLabelComponent = rememberAxisLabelComponent(textSize = monthLabelStrategy.fontSizeSp.sp)
        CartesianChartHost(
            chart = rememberSeasonalCurveChart(
                visuals.curveColor,
                monthFormatter,
                multiplierFormatter,
                visuals.todayMarkerDecoration,
                monthLabelComponent,
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            // `rememberVicoZoomState(zoomEnabled = false)`'s *default* `initialZoom` is
            // `Zoom.max(Zoom.fixed(), Zoom.Content)` — the larger of a hardcoded 1.0x and the
            // fit-to-bounds value (verified against Vico 2.5.2's `Zoom.kt`/`VicoZoomState.kt`
            // source, the pinned version in `app/build.gradle.kts`). Our `LineCartesianLayer` sets
            // no `pointProvider`, so its un-zoomed base `xSpacing` is exactly
            // `Defaults.POINT_SPACING` (32dp) per x-unit (`LineCartesianLayer.updateDimensions()`)
            // — 12 months therefore has a ~384dp un-zoomed base width. On any card narrower than
            // that (common inside the Plant Detail inline-settings card and the Settings screen),
            // the fit-to-bounds value is below 1.0, so `Zoom.max` picks the 1.0 floor instead,
            // pinning the chart's real content width at ~384dp regardless of the container. Since
            // scroll is disabled above, that excess silently overflows past the right edge — where
            // Nov/Dec sit — and gets hard-clipped by the Composable's own bounds (#628 follow-up:
            // still cut off around November after the #621 responsive-label fix, which only ever
            // addressed per-label text sizing, not this separate whole-chart-doesn't-fit issue).
            // `Zoom.Content` alone has no such floor, so the chart always shrinks (or grows) to
            // exactly fill whatever width `BoxWithConstraints` measured above, on every screen size.
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        )
    }
}

@Composable
private fun rememberSeasonalCurveChart(
    curveColor: Color,
    monthFormatter: CartesianValueFormatter,
    multiplierFormatter: CartesianValueFormatter,
    todayMarkerDecoration: Decoration,
    monthLabelComponent: TextComponent?,
) = rememberCartesianChart(
    rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(
            LineCartesianLayer.rememberLine(
                fill = LineCartesianLayer.LineFill.single(fill(curveColor)),
            )
        ),
        rangeProvider = remember {
            CartesianLayerRangeProvider.fixed(
                minX = 0.0,
                maxX = MONTHS_IN_YEAR.toDouble() - 0.001,
                minY = 1.0 - Y_AXIS_HALF_RANGE,
                maxY = 1.0 + Y_AXIS_HALF_RANGE,
            )
        },
    ),
    startAxis = VerticalAxis.rememberStart(
        valueFormatter = multiplierFormatter,
        itemPlacer = remember { VerticalAxis.ItemPlacer.step(step = { Y_AXIS_STEP }) },
    ),
    bottomAxis = HorizontalAxis.rememberBottom(
        label = monthLabelComponent,
        valueFormatter = monthFormatter,
        itemPlacer = remember { HorizontalAxis.ItemPlacer.aligned(spacing = { 1 }) },
    ),
    decorations = listOf(todayMarkerDecoration),
    // Vico's default x-step is the GCD of every consecutive x-delta in the series
    // (CartesianChartModel.getDefaultXStep). With ~365 daily-sampled points, day-within-month
    // fractions from unequal month lengths (28-31 days) share no common divisor above the
    // 4-decimal rounding quantum, so the inferred step collapses to ~0.0001 instead of 1 month —
    // aligned(spacing = { 1 }) then places ticks far too densely and they all round to month
    // index 0 ("Jan" repeated). Pin the step explicitly: 1 x-unit is always 1 calendar month here.
    getXStep = { _, _, _ -> 1.0 },
)
