package com.yapt.planttracker.ui.components

import kotlin.math.roundToInt

/**
 * Fraction of a month elapsed by [dayOfMonth] (1-indexed) out of [daysInMonth] days, rounded to 4
 * decimal places — Vico 2.0.0 throws `IllegalArgumentException` on higher-precision x values (its
 * GCD-based internal precision handling). Shared by `WateringHistoryChart.kt`'s per-event month
 * index and `SeasonalWateringCurveChart.kt`'s calendar month index.
 */
internal fun fractionalDayOfMonth(dayOfMonth: Int, daysInMonth: Int): Float {
    val fraction = (dayOfMonth - 1).toFloat() / daysInMonth
    return (fraction * 10000).roundToInt() / 10000f
}
