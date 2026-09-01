package com.yapt.planttracker.ui.components

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val MONTHS_IN_YEAR = 12

/** Vico's own axis-label default ([com.patrykandpatrick.vico.core.common.Defaults.AXIS_LABEL_SIZE]). */
private const val AXIS_LABEL_NORMAL_SP = 12f

/**
 * Floor below which month labels stop shrinking (#621) — the recommended 9sp reads as roughly the
 * smallest legible on-device size without clipping ascenders/descenders at [AXIS_LABEL_NORMAL_SP]'s
 * `Typeface.DEFAULT`.
 */
private const val MONTH_LABEL_FLOOR_SP = 9f

/** Sample string used only to estimate the y-axis's reserved width; not itself rendered. */
private const val Y_AXIS_WIDTH_SAMPLE = "1.50×"

/** Slack (margins + padding + tick length) around the y-axis label, matching Vico's axis defaults. */
private const val Y_AXIS_RESERVED_SLACK_DP = 16f

internal enum class MonthLabelTextCase { ABBREVIATED, SINGLE_LETTER }

/**
 * Result of [resolveMonthLabelStrategy]'s width→label-format decision (#621). [thinned] means only
 * alternating months carry label text — the rest of the ticks are still drawn, just unlabeled.
 */
internal data class MonthLabelStrategy(
    val textCase: MonthLabelTextCase,
    val fontSizeSp: Float,
    val thinned: Boolean,
)

/**
 * Inputs [bestFittingFontSizeSp] needs beyond the label list/tick count themselves, bundled to stay
 * under detekt's parameter-count limit. [measureTextWidthPx] is injected so the whole resolution
 * stays a plain, deterministic function testable without Compose/Vico/Android text-measurement —
 * production wiring supplies a real text-width measurer.
 */
private data class LabelSizingContext(
    val availableWidthPx: Float,
    val normalFontSizeSp: Float,
    val floorFontSizeSp: Float,
    val measureTextWidthPx: (text: String, fontSizeSp: Float) -> Float,
)

private fun widestLabelWidthPx(labels: List<String>, fontSizeSp: Float, context: LabelSizingContext): Float =
    labels.maxOf { context.measureTextWidthPx(it, fontSizeSp) }

private fun fitsAtSize(labels: List<String>, tickCount: Int, fontSizeSp: Float, context: LabelSizingContext): Boolean =
    context.availableWidthPx > 0f &&
        widestLabelWidthPx(labels, fontSizeSp, context) <= context.availableWidthPx / tickCount

/**
 * The largest font size in `[context.floorFontSizeSp, context.normalFontSizeSp]` at which every
 * label in [labels] fits its slot (`context.availableWidthPx / tickCount`), or `null` if even the
 * floor size doesn't fit.
 */
private fun bestFittingFontSizeSp(labels: List<String>, tickCount: Int, context: LabelSizingContext): Float? {
    val widestAtNormal = widestLabelWidthPx(labels, context.normalFontSizeSp, context)
    val slotWidthPx = context.availableWidthPx / tickCount
    val proportionalSp = if (widestAtNormal > 0f) {
        (context.normalFontSizeSp * (slotWidthPx / widestAtNormal))
            .coerceIn(context.floorFontSizeSp, context.normalFontSizeSp)
    } else {
        context.floorFontSizeSp
    }
    return when {
        fitsAtSize(labels, tickCount, context.normalFontSizeSp, context) -> context.normalFontSizeSp
        fitsAtSize(labels, tickCount, context.floorFontSizeSp, context) -> proportionalSp
        else -> null
    }
}

/**
 * Pure width→label-format decision for the month axis (#621, product-resolved fallback order):
 * 1. `"MMM"` at [normalFontSizeSp].
 * 2. `"MMM"` shrunk continuously down to [floorFontSizeSp].
 * 3. Single-letter labels, re-running the same shrink-to-floor check.
 * 4. Single-letter labels thinned to alternating months — last resort, only once single letters
 *    still don't fit at the floor size with all 12 months labeled.
 */
internal fun resolveMonthLabelStrategy(
    availableWidthPx: Float,
    monthLabels: List<String>,
    measureTextWidthPx: (text: String, fontSizeSp: Float) -> Float,
    normalFontSizeSp: Float = AXIS_LABEL_NORMAL_SP,
    floorFontSizeSp: Float = MONTH_LABEL_FLOOR_SP,
): MonthLabelStrategy {
    require(monthLabels.size == MONTHS_IN_YEAR) { "Expected $MONTHS_IN_YEAR month labels, got ${monthLabels.size}" }
    val context = LabelSizingContext(availableWidthPx, normalFontSizeSp, floorFontSizeSp, measureTextWidthPx)
    val singleLetterLabels = monthLabels.map { it.take(1) }

    val abbreviatedSize = bestFittingFontSizeSp(monthLabels, MONTHS_IN_YEAR, context)
    val singleLetterSize = if (abbreviatedSize == null) {
        bestFittingFontSizeSp(singleLetterLabels, MONTHS_IN_YEAR, context)
    } else {
        null
    }
    val thinnedSize = if (abbreviatedSize == null && singleLetterSize == null) {
        bestFittingFontSizeSp(singleLetterLabels, MONTHS_IN_YEAR / 2, context) ?: floorFontSizeSp
    } else {
        null
    }

    return when {
        abbreviatedSize != null -> MonthLabelStrategy(MonthLabelTextCase.ABBREVIATED, abbreviatedSize, thinned = false)
        singleLetterSize != null ->
            MonthLabelStrategy(MonthLabelTextCase.SINGLE_LETTER, singleLetterSize, thinned = false)
        else -> MonthLabelStrategy(MonthLabelTextCase.SINGLE_LETTER, thinnedSize ?: floorFontSizeSp, thinned = true)
    }
}

/** Builds the tick-index→label map for [strategy], leaving thinned-out ticks blank (still drawn, unlabeled). */
internal fun buildMonthLabelMap(baseLabels: List<String>, strategy: MonthLabelStrategy): Map<Int, String> {
    val labels = when (strategy.textCase) {
        MonthLabelTextCase.ABBREVIATED -> baseLabels
        MonthLabelTextCase.SINGLE_LETTER -> baseLabels.map { it.take(1) }
    }
    return labels.mapIndexed { index, label ->
        index to if (strategy.thinned && index % 2 != 0) " " else label
    }.toMap()
}

internal fun measureLabelWidthPx(text: String, fontSizeSp: Float, density: Density): Float {
    val paint = android.graphics.Paint().apply {
        typeface = android.graphics.Typeface.DEFAULT
        textSize = with(density) { fontSizeSp.sp.toPx() }
    }
    return paint.measureText(text)
}

/**
 * The y-axis label format is fixed ("0.50×"–"1.50×", never changed by this fix), so its reserved
 * width can be estimated once per measured [density] rather than resolved by the pure function —
 * [resolveMonthLabelStrategy] only ever sees the plot width actually left for month ticks.
 */
internal fun estimateYAxisReservedWidthPx(density: Density): Float {
    val labelWidthPx = measureLabelWidthPx(Y_AXIS_WIDTH_SAMPLE, AXIS_LABEL_NORMAL_SP, density)
    val slackPx = with(density) { Y_AXIS_RESERVED_SLACK_DP.dp.toPx() }
    return labelWidthPx + slackPx
}
