package com.yapt.planttracker.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure-JVM tests for [CareSchedule.bootstrapBaseInterval] (#571 Part B): cold-starting a season-neutral
 * base interval and confidence from a plant's own watering history. See technical ADR-0023.
 */
class CareScheduleBootstrapTest {

    private fun ms(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val flatSeason: (LocalDate) -> Double = { 1.0 }

    @Test
    fun `empty history returns null`() {
        assertNull(CareSchedule.bootstrapBaseInterval(emptyList(), flatSeason))
    }

    @Test
    fun `a single timestamp (zero gaps) returns null`() {
        assertNull(CareSchedule.bootstrapBaseInterval(listOf(ms(LocalDate.of(2026, 1, 1))), flatSeason))
    }

    @Test
    fun `one sample (a single gap) computes a value below the application threshold`() {
        val timestamps = listOf(
            ms(LocalDate.of(2026, 1, 1)),
            ms(LocalDate.of(2026, 1, 8))
        )
        val result = CareSchedule.bootstrapBaseInterval(timestamps, flatSeason)
        requireNotNull(result)
        assertEquals(1, result.gapCount)
        assertEquals(7.0, result.baseIntervalDays, 1e-9)
        // Caller-side threshold, not asserted by the function itself:
        assertTrue(result.gapCount < CareSchedule.MIN_BOOTSTRAP_GAPS)
    }

    @Test
    fun `samples spread across seasons are de-seasonalized before taking the median`() {
        // Gaps of 7 raw days each, but taken under alternating season factors (0.8x / 1.2x) — a
        // seasonally-blind median of the raw gaps would be skewed; de-seasonalizing first must recover
        // the same underlying 7-day cadence.
        val dates = listOf(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 8),
            LocalDate.of(2026, 1, 15),
            LocalDate.of(2026, 1, 22),
            LocalDate.of(2026, 1, 29)
        )
        val seasonForDate: (LocalDate) -> Double = { date ->
            if (date.dayOfMonth % 14 == 8) 0.8 else 1.2
        }
        val result = CareSchedule.bootstrapBaseInterval(dates.map { ms(it) }, seasonForDate)
        requireNotNull(result)
        assertEquals(4, result.gapCount)
        // gaps: 7/0.8=8.75, 7/1.2=5.833.., 7/0.8=8.75, 7/1.2=5.833.. -> median of
        // [5.833.., 5.833.., 8.75, 8.75] = (5.833..+8.75)/2 = 7.291666..
        assertEquals(7.291666, result.baseIntervalDays, 1e-3)
    }

    @Test
    fun `an extreme outlier gap is absorbed by the median rather than skewing it`() {
        val dates = listOf(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 8),
            LocalDate.of(2026, 1, 15),
            LocalDate.of(2026, 1, 22),
            LocalDate.of(2026, 3, 23) // a 60-day holiday-induced gap
        )
        val result = CareSchedule.bootstrapBaseInterval(dates.map { ms(it) }, flatSeason)
        requireNotNull(result)
        assertEquals(4, result.gapCount)
        // gaps: 7, 7, 7, 60 -> median (sorted [7,7,7,60], size 4) = (7+7)/2 = 7
        assertEquals(7.0, result.baseIntervalDays, 1e-9)
    }

    @Test
    fun `all-same-month history still de-seasonalizes correctly (near-constant season factor)`() {
        val dates = listOf(
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 8),
            LocalDate.of(2026, 6, 15),
            LocalDate.of(2026, 6, 22)
        )
        // A season factor that varies negligibly within the same month.
        val seasonForDate: (LocalDate) -> Double = { date -> 1.0 + date.dayOfMonth * 0.00001 }
        val result = CareSchedule.bootstrapBaseInterval(dates.map { ms(it) }, seasonForDate)
        requireNotNull(result)
        assertEquals(3, result.gapCount)
        // Season is ~1.00008-1.00022 throughout, so de-seasonalizing is a near no-op: median stays ~7.
        assertEquals(7.0, result.baseIntervalDays, 0.01)
    }

    @Test
    fun `confidence is gap count divided by three, capped at five`() {
        // 3 gaps -> 1, 6 gaps -> 2, 15+ gaps -> capped at 5.
        val threeGapDates = (0..3).map { LocalDate.of(2026, 1, 1).plusDays((it * 7).toLong()) }
        assertEquals(1, CareSchedule.bootstrapBaseInterval(threeGapDates.map { ms(it) }, flatSeason)!!.confidence)

        val sixGapDates = (0..6).map { LocalDate.of(2026, 1, 1).plusDays((it * 7).toLong()) }
        assertEquals(2, CareSchedule.bootstrapBaseInterval(sixGapDates.map { ms(it) }, flatSeason)!!.confidence)

        val manyGapDates = (0..30).map { LocalDate.of(2026, 1, 1).plusDays((it * 7).toLong()) }
        assertEquals(5, CareSchedule.bootstrapBaseInterval(manyGapDates.map { ms(it) }, flatSeason)!!.confidence)
    }

    @Test
    fun `timestamps out of order are sorted before computing gaps`() {
        val outOfOrder = listOf(
            ms(LocalDate.of(2026, 1, 15)),
            ms(LocalDate.of(2026, 1, 1)),
            ms(LocalDate.of(2026, 1, 8))
        )
        val result = CareSchedule.bootstrapBaseInterval(outOfOrder, flatSeason)
        requireNotNull(result)
        assertEquals(2, result.gapCount)
        assertEquals(7.0, result.baseIntervalDays, 1e-9)
    }

    @Test
    fun `result is floored at the minimum adaptive interval`() {
        val sameDayTwice = listOf(
            ms(LocalDate.of(2026, 1, 1)),
            ms(LocalDate.of(2026, 1, 1))
        )
        val result = CareSchedule.bootstrapBaseInterval(sameDayTwice, flatSeason)
        requireNotNull(result)
        assertTrue(result.baseIntervalDays >= 1.0)
    }
}
