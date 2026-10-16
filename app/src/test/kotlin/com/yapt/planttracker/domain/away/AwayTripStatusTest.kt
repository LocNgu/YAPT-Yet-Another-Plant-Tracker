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

    @Test
    fun `the status follows the earliest unfinished trip`() {
        val past = trip(today.minusDays(30), today.minusDays(20), id = 1L)
        val next = trip(today.plusDays(2), today.plusDays(4), id = 2L)

        val status = awayTripStatus(listOf(past, next), today)

        assertEquals(AwayTripStatus.Upcoming(next), status)
    }
}
