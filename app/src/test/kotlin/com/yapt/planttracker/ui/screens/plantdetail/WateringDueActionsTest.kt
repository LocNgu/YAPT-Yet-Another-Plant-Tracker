package com.yapt.planttracker.ui.screens.plantdetail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [isOnOrAfterLocalToday] boundary coverage (#508 review fix) — the [DatePicker] operates on UTC
 * midnight, but the picked date is always reinterpreted as a local calendar day downstream
 * ([utcMidnightMsToLocalStartOfDayMillis]), so "today" must be evaluated in the caller's local zone,
 * not UTC. Exercises a UTC-midnight instant that is one local-zone's "yesterday" relative to a
 * positive-offset zone (Asia/Tokyo, UTC+9) and one negative-offset zone (America/Los_Angeles).
 */
class WateringDueActionsTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val losAngeles = ZoneId.of("America/Los_Angeles")

    @Test
    fun `today's UTC-midnight instant is selectable in Tokyo`() {
        val todayUtcMidnight = LocalDate.now(tokyo).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertTrue(isOnOrAfterLocalToday(todayUtcMidnight, tokyo))
    }

    @Test
    fun `UTC-midnight instant still on yesterday's UTC calendar day is not selectable in Tokyo`() {
        // Asia/Tokyo is UTC+9: at any local Tokyo hour, the UTC calendar day can lag Tokyo's by one
        // day for up to 9 hours. Yesterday's UTC-midnight is unambiguously before Tokyo's local today.
        val yesterdayUtcMidnight =
            LocalDate.now(tokyo).minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertFalse(isOnOrAfterLocalToday(yesterdayUtcMidnight, tokyo))
    }

    @Test
    fun `today's UTC-midnight instant is selectable in Los Angeles`() {
        val todayUtcMidnight =
            LocalDate.now(losAngeles).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertTrue(isOnOrAfterLocalToday(todayUtcMidnight, losAngeles))
    }

    @Test
    fun `tomorrow's UTC-midnight instant is selectable in Los Angeles`() {
        // America/Los_Angeles is UTC-8/-7: local "today" can still be behind the UTC calendar day by
        // up to 8 hours, so tomorrow's UTC-midnight instant must remain selectable, not excluded.
        val tomorrowUtcMidnight =
            LocalDate.now(losAngeles).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertTrue(isOnOrAfterLocalToday(tomorrowUtcMidnight, losAngeles))
    }

    @Test
    fun `two days ago UTC-midnight instant is not selectable in Los Angeles`() {
        val twoDaysAgoUtcMidnight =
            LocalDate.now(losAngeles).minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertFalse(isOnOrAfterLocalToday(twoDaysAgoUtcMidnight, losAngeles))
    }
}
