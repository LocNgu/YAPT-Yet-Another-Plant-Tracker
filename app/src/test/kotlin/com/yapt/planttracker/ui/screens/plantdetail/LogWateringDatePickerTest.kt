package com.yapt.planttracker.ui.screens.plantdetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone

/**
 * [isOnOrBeforeLocalToday]/[utcMidnightMsToLoggedAtMillis] unit coverage (#654 review round 1) — both
 * are plain JVM-testable pure functions backing [LogWateringDatePickerDialog], previously untested
 * (only indirectly exercised via instrumented picker tests). Mirrors [WateringDueActionsTest]'s
 * injectable-`today` convention for [isOnOrBeforeLocalToday]'s inverse ([isOnOrAfterLocalToday]) — like
 * that function, the candidate is always interpreted in UTC; only `today`'s *default* consults `zoneId`,
 * so every test here pins `today` explicitly rather than relying on a device zone/clock at test time.
 */
class LogWateringDatePickerTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test
    fun `today's UTC-midnight instant is selectable`() {
        val today = LocalDate.of(2026, 1, 15)
        val candidateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertTrue(isOnOrBeforeLocalToday(candidateMillis, tokyo, today = today))
    }

    @Test
    fun `a past day's UTC-midnight instant is selectable`() {
        val today = LocalDate.of(2026, 1, 15)
        val yesterday = today.minusDays(1)
        val candidateMillis = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertTrue(isOnOrBeforeLocalToday(candidateMillis, tokyo, today = today))
    }

    @Test
    fun `a future day's UTC-midnight instant is not selectable`() {
        val today = LocalDate.of(2026, 1, 15)
        val tomorrow = today.plusDays(1)
        val candidateMillis = tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertFalse(isOnOrBeforeLocalToday(candidateMillis, tokyo, today = today))
    }

    // The candidate is always interpreted in UTC (mirroring `isOnOrAfterLocalToday`'s identical
    // pattern) — only the injectable `today` default consults `zoneId`, so pinning `today` to a
    // calendar day one day ahead of the UTC-encoded candidate is enough to move the boundary,
    // without needing the candidate itself to shift by zone.
    @Test
    fun `a day one day behind an explicit today is still selectable (not just today itself)`() {
        val today = LocalDate.of(2026, 1, 16)
        val candidateMillis = LocalDate.of(2026, 1, 15).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertTrue(isOnOrBeforeLocalToday(candidateMillis, tokyo, today = today))
    }

    @Test
    fun `utcMidnightMsToLoggedAtMillis copies the picked calendar day onto the current time-of-day`() {
        val pickedUtcMidnight = LocalDate.of(2026, 1, 10).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val wallClockNow = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 15, 14, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val result = utcMidnightMsToLoggedAtMillis(pickedUtcMidnight, wallClockNow)

        val resultCal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(2026, resultCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, resultCal.get(Calendar.MONTH))
        assertEquals(10, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, resultCal.get(Calendar.MINUTE))
    }

    @Test
    fun `utcMidnightMsToLoggedAtMillis reinterprets the picker's UTC day as a local calendar day`() {
        // The picker's UTC-midnight for Jan 10 encodes a different instant than the device's local
        // midnight for Jan 10 whenever the local zone isn't UTC — pickerCal must read Jan 10's Y/M/D
        // fields from the UTC calendar, not from a UTC-offset-shifted local reinterpretation.
        val pickerUtcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.JANUARY, 10, 0, 0, 0)
        }
        val wallClockNow = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 3, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val result = utcMidnightMsToLoggedAtMillis(pickerUtcCal.timeInMillis, wallClockNow)

        val resultCal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(2026, resultCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, resultCal.get(Calendar.MONTH))
        assertEquals(10, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, resultCal.get(Calendar.HOUR_OF_DAY))
    }
}
