package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.AwayPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AwayTripStatusTest {

    private val today = LocalDate.of(2026, 10, 14)
    private fun trip(first: LocalDate, last: LocalDate, id: Long = 1L) = AwayPeriod(id, first, last)

    @Test
    fun `no trips means no status and no banner`() {
        val status = awayTripStatus(emptyList(), today)

        assertEquals(AwayTripStatus.None, status)
        assertFalse(status.showsBanner(today))
    }

    @Test
    fun `a trip that has ended is no status and no banner`() {
        val status = awayTripStatus(listOf(trip(today.minusDays(10), today.minusDays(1))), today)

        assertEquals(AwayTripStatus.None, status)
        assertFalse(status.showsBanner(today))
    }

    @Test
    fun `the first and the last day of a trip are away and show the banner`() {
        val trip = trip(today, today.plusDays(3))

        assertTrue(awayTripStatus(listOf(trip), today) is AwayTripStatus.Away)
        assertTrue(awayTripStatus(listOf(trip), today.plusDays(3)) is AwayTripStatus.Away)
        assertTrue(awayTripStatus(listOf(trip), today.plusDays(3)).showsBanner(today.plusDays(3)))
    }

    @Test
    fun `the day after the last day the banner is gone`() {
        val trip = trip(today, today.plusDays(3))

        assertEquals(AwayTripStatus.None, awayTripStatus(listOf(trip), today.plusDays(4)))
    }

    @Test
    fun `an upcoming trip shows the banner from the lead window and not before`() {
        val first = today.plusDays(AWAY_BANNER_LEAD_DAYS)
        val trip = trip(first, first.plusDays(5))

        val inWindow = awayTripStatus(listOf(trip), today)
        val tooEarly = awayTripStatus(listOf(trip), today.minusDays(1))

        assertTrue(inWindow is AwayTripStatus.Upcoming)
        assertTrue(inWindow.showsBanner(today))
        assertTrue(tooEarly is AwayTripStatus.Upcoming)
        assertFalse(tooEarly.showsBanner(today.minusDays(1)))
    }

    @Test
    fun `the banner lead window is a week`() {
        assertEquals(7L, AWAY_BANNER_LEAD_DAYS)
    }

    private fun statusFor(daysOut: Long, createdAt: Long = 1L) = awayTripStatus(
        listOf(AwayPeriod(1L, today.plusDays(daysOut), today.plusDays(daysOut + 5), createdAt = createdAt)),
        today
    )

    @Test
    fun `before you leave opens a week out and not at eight days`() {
        assertFalse(statusFor(AWAY_BANNER_LEAD_DAYS + 1).canOpenBeforeYouLeave(today))
        assertTrue(statusFor(AWAY_BANNER_LEAD_DAYS).canOpenBeforeYouLeave(today))
    }

    @Test
    fun `before you leave stays open through the last day at home and closes on the first day`() {
        assertTrue(statusFor(daysOut = 2).canOpenBeforeYouLeave(today))
        assertTrue(statusFor(daysOut = 1).canOpenBeforeYouLeave(today))
        assertTrue(statusFor(daysOut = 0) is AwayTripStatus.Away)
        assertFalse(statusFor(daysOut = 0).canOpenBeforeYouLeave(today))
    }

    @Test
    fun `before you leave is open exactly from the banner lead days to the day before the trip`() {
        for (daysOut in 0L..AWAY_BANNER_LEAD_DAYS + 3) {
            assertEquals(
                "daysOut=$daysOut",
                daysOut in 1L..AWAY_BANNER_LEAD_DAYS,
                statusFor(daysOut).canOpenBeforeYouLeave(today)
            )
        }
    }

    @Test
    fun `before you leave is closed while away, after the trip and with no trip`() {
        val away = awayTripStatus(listOf(trip(today.minusDays(3), today.plusDays(3))), today)
        val ended = awayTripStatus(listOf(trip(today.minusDays(10), today.minusDays(1))), today)

        assertTrue(away is AwayTripStatus.Away)
        assertFalse(away.canOpenBeforeYouLeave(today))
        assertFalse(ended.canOpenBeforeYouLeave(today))
        assertFalse(AwayTripStatus.None.canOpenBeforeYouLeave(today))
    }

    @Test
    fun `the window depends on dates only, never on when the trip was saved`() {
        assertTrue(statusFor(daysOut = 3, createdAt = 0L).canOpenBeforeYouLeave(today))
        assertTrue(statusFor(daysOut = 3, createdAt = Long.MAX_VALUE).canOpenBeforeYouLeave(today))
        assertFalse(statusFor(daysOut = 9, createdAt = 0L).canOpenBeforeYouLeave(today))
        assertFalse(statusFor(daysOut = 9, createdAt = Long.MAX_VALUE).canOpenBeforeYouLeave(today))
    }

    @Test
    fun `an upcoming status whose trip already started is not open`() {
        val started = AwayTripStatus.Upcoming(trip(today, today.plusDays(4)))

        assertFalse(started.canOpenBeforeYouLeave(today))
    }

    @Test
    fun `the status follows the earliest unfinished trip`() {
        val past = trip(today.minusDays(30), today.minusDays(20), id = 1L)
        val next = trip(today.plusDays(2), today.plusDays(4), id = 2L)

        val status = awayTripStatus(listOf(past, next), today)

        assertEquals(AwayTripStatus.Upcoming(next), status)
    }
}
