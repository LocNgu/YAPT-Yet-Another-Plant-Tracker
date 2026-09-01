package com.yapt.planttracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
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
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalCurvePoint
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.SeasonalWateringCurveSampler
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val Y_AXIS_HALF_RANGE = 0.5
private const val Y_AXIS_STEP = 0.25
private const val MONTHS_IN_YEAR = 12
private const val TICK_MATCH_EPSILON = 0.001
private val MonthLabelsKey = ExtraStore.Key<Map<Int, String>>()
private val TodayPointKey = ExtraStore.Key<TodayCurvePoint>()

internal data class TodayCurvePoint(val x: Float, val y: Float)

/**
 * Bundles the Plant-Detail-only params of [SeasonalWateringCurveChart] — kept together to stay under
 * Detekt's `LongParameterList` threshold, mirroring `CustomReminderActions`'s pattern elsewhere in the
 * codebase — rather than adding more individual params. Both default to Settings' posture (no plant in
 * scope): not pinned, no base interval to convert the axis to days with.
 */
internal data class SeasonalCurvePlantContext(
    val isPinned: Boolean = false,
    val baseIntervalDays: Double? = null,
)

/**
 * Fractional month-index x-coordinate for [date] (Jan 1 = 0.0, Dec 31 ≈ 11.97), matching
 * `WateringHistoryChart.kt`'s monthly-tick convention so the same `HorizontalAxis.ItemPlacer
 * .aligned(spacing = { 1 })` places ticks exactly at month boundaries. Rounded to 4 decimal places
 * — Vico 2.0.0 throws `IllegalArgumentException` on higher-precision x values (its GCD-based
 * internal precision handling).
 */
internal fun monthIndexFor(date: LocalDate): Float =
    (date.monthValue - 1) + fractionalDayOfMonth(date.dayOfMonth, date.lengthOfMonth())

/** The fixed multiplier ticks the y-axis renders, `[0.5, 0.75, 1.0, 1.25, 1.5]` — unchanged by
 *  [baseIntervalDays] (Plant Detail's days-format axis), which only converts the *label* shown at
 *  each of these tick positions, never the axis's own numeric range/step. */
internal fun seasonalCurveYAxisTicks(): List<Double> {
    val stepCount = (2 * Y_AXIS_HALF_RANGE / Y_AXIS_STEP).roundToInt()
    return (0..stepCount).map { (1.0 - Y_AXIS_HALF_RANGE) + it * Y_AXIS_STEP }
}

/**
 * Whole-day tick labels (`"Nd"`, `round(baseIntervalDays × multiplier)`) for [ticks] in axis order.
 * When a tick's rounded day value equals the immediately preceding tick's, the later tick's label is
 * blanked instead of repeated — the first tick is never blanked (#622).
 */
internal fun seasonalCurveDayTickLabels(
    baseIntervalDays: Double,
    ticks: List<Double> = seasonalCurveYAxisTicks(),
): List<String> {
    var previousDays: Int? = null
    return ticks.map { multiplier ->
        val days = (multiplier * baseIntervalDays).roundToInt()
        val label = if (days == previousDays) "" else "${days}d"
        previousDays = days
        label
    }
}

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
 * curve's *height* within a constant frame, rather than rescaling the axis each time.
 * [plantContext]'s `isPinned` (Plant Detail only) grays the curve out and adds an inline note — the
 * plant's due dates ignore this curve entirely while pinned (#578).
 *
 * [plantContext]'s `baseIntervalDays` (#622) is `null` on the Settings screen call site, where there
 * is no per-plant base interval to anchor days to — the axis and captions stay the raw multiplier
 * there, byte-for-byte unchanged. On Plant Detail it is non-null, and the axis/captions switch to
 * whole days (`round(baseIntervalDays × multiplier)`) instead — only the label text changes, never
 * the axis's numeric range/step.
 */
@Composable
internal fun SeasonalWateringCurveChart(
    amplitude: Double,
    hemisphere: Hemisphere,
    modifier: Modifier = Modifier,
    showHemisphereCaption: Boolean = false,
    plantContext: SeasonalCurvePlantContext = SeasonalCurvePlantContext(),
) {
    val isPinned = plantContext.isPinned
    val baseIntervalDays = plantContext.baseIntervalDays
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
            baseIntervalDays = baseIntervalDays,
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
    baseIntervalDays: Double?,
) {
    val minMultiplier = remember(points) { points.minOf { it.multiplier } }
    val maxMultiplier = remember(points) { points.maxOf { it.multiplier } }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.alpha(if (isPinned) 0.45f else 1f)) {
            SeasonalCurveVicoChart(points, today, todayMultiplier, baseIntervalDays)
        }

        if (baseIntervalDays != null) {
            val minDays = (minMultiplier * baseIntervalDays).roundToInt()
            val maxDays = (maxMultiplier * baseIntervalDays).roundToInt()
            val todayDays = (todayMultiplier * baseIntervalDays).roundToInt()
            Text(
                text = stringResource(R.string.seasonal_curve_range_days, minDays, maxDays),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Text(
                text = stringResource(R.string.seasonal_curve_today_days, todayDays),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        } else {
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
    baseIntervalDays: Double?,
) {
    val monthLabels = remember {
        val fmt = DateTimeFormatter.ofPattern("MMM")
        (0 until MONTHS_IN_YEAR).associateWith { fmt.format(LocalDate.of(2001, it + 1, 1)) }
    }

    val curveColor = MaterialTheme.colorScheme.primary
    val todayDotColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val todayGuidelineColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f).toArgb()
    val todayMarkerDecoration = remember(todayDotColor, todayGuidelineColor) {
        TodayMarkerDecoration(todayDotColor, todayGuidelineColor)
    }

    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points, monthLabels, todayMultiplier) {
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = points.map { monthIndexFor(it.date) },
                    y = points.map { it.multiplier.toFloat() }
                )
            }
            extras { store ->
                store[MonthLabelsKey] = monthLabels
                store[TodayPointKey] = TodayCurvePoint(monthIndexFor(today), todayMultiplier.toFloat())
            }
        }
    }

    val monthFormatter = remember {
        CartesianValueFormatter { context, x, _ ->
            context.model.extraStore.getOrNull(MonthLabelsKey)?.get(x.roundToInt()) ?: " "
        }
    }
    val yAxisFormatter = rememberSeasonalCurveYAxisFormatter(baseIntervalDays)

    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            chart = rememberSeasonalCurveChart(curveColor, monthFormatter, yAxisFormatter, todayMarkerDecoration),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false),
        )
    }
}

/**
 * The multiplier formatter (`"%.2f×"`) when [baseIntervalDays] is `null` (Settings), or a whole-day
 * formatter (`"Nd"`, with adjacent-duplicate blanking — see [seasonalCurveDayTickLabels]) when it's
 * non-null (Plant Detail, #622). Only the label text differs; the axis's numeric range/step doesn't.
 */
@Composable
private fun rememberSeasonalCurveYAxisFormatter(baseIntervalDays: Double?): CartesianValueFormatter {
    val dayTicks = remember { seasonalCurveYAxisTicks() }
    return remember(baseIntervalDays, dayTicks) {
        if (baseIntervalDays == null) {
            return@remember CartesianValueFormatter { _, y, _ -> String.format(Locale.getDefault(), "%.2f×", y) }
        }
        val labels = seasonalCurveDayTickLabels(baseIntervalDays, dayTicks)
        CartesianValueFormatter { _, y, _ ->
            val tickIndex = dayTicks.indexOfFirst { abs(it - y) < TICK_MATCH_EPSILON }
            // Believed unreachable: the fixed ticks are exact dyadic doubles and step()'s
            // fixed-range item placer only ever calls this formatter with one of them.
            if (tickIndex >= 0) labels[tickIndex] else "${(y * baseIntervalDays).roundToInt()}d"
        }
    }
}

@Composable
private fun rememberSeasonalCurveChart(
    curveColor: Color,
    monthFormatter: CartesianValueFormatter,
    yAxisFormatter: CartesianValueFormatter,
    todayMarkerDecoration: Decoration,
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
        valueFormatter = yAxisFormatter,
        itemPlacer = remember { VerticalAxis.ItemPlacer.step(step = { Y_AXIS_STEP }) },
    ),
    bottomAxis = HorizontalAxis.rememberBottom(
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
