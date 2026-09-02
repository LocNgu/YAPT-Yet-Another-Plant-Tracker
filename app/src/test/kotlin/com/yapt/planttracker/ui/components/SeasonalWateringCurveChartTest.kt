package com.yapt.planttracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonalCurveYAxisTicksTest {

    @Test
    fun fixedFiveTicks_halfRangeAndStep() {
        assertEquals(listOf(0.5, 0.75, 1.0, 1.25, 1.5), seasonalCurveYAxisTicks())
    }
}

class SeasonalCurveDayTickLabelsTest {

    @Test
    fun noDuplicates_everyTickLabeled() {
        // baseIntervalDays = 20 → 10, 15, 20, 25, 30 — all distinct.
        val labels = seasonalCurveDayTickLabels(20.0)
        assertEquals(listOf("10d", "15d", "20d", "25d", "30d"), labels)
    }

    @Test
    fun adjacentDuplicate_laterTickBlanked() {
        // baseIntervalDays = 2 → ticks round to 1, 2, 2, 3, 3 — two adjacent-pair collisions;
        // each pair's later tick is blanked, its earlier tick keeps the label.
        val labels = seasonalCurveDayTickLabels(2.0)
        val roundedDays = seasonalCurveYAxisTicks().map { Math.round(it * 2.0).toInt() }
        assertEquals(listOf(1, 2, 2, 3, 3), roundedDays)
        assertEquals(listOf("1d", "2d", "", "3d", ""), labels)
    }

    @Test
    fun firstTickNeverBlanked_evenWithDegenerateBase() {
        val labels = seasonalCurveDayTickLabels(0.0)
        assertEquals(listOf("0d", "", "", "", ""), labels)
    }

    @Test
    fun customTicks_walkedInGivenOrder() {
        // 10, 10.2, 10.4, 20 → rounds to 10, 10, 10, 20: two interior duplicates blanked in a row,
        // the run's first tick keeps its label, and the fresh value after the run is never blanked.
        val labels = seasonalCurveDayTickLabels(10.0, ticks = listOf(1.0, 1.02, 1.04, 2.0))
        assertEquals(listOf("10d", "", "", "20d"), labels)
    }

    @Test
    fun roundingMatchesRoundHalfUp() {
        // 0.75 * 10 = 7.5 → roundToInt() rounds half-up to 8.
        val labels = seasonalCurveDayTickLabels(10.0)
        assertEquals("8d", labels[1])
    }
}
