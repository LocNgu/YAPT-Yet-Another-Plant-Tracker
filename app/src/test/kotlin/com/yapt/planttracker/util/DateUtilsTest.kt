package com.yapt.planttracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class DateUtilsTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `formatRelative 0 days returns Today`() {
        val timestamp = now - TimeUnit.HOURS.toMillis(1)
        assertEquals("Today", DateUtils.formatRelative(timestamp, now))
    }

    @Test
    fun `formatRelative 1 day returns Yesterday`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(1)
        assertEquals("Yesterday", DateUtils.formatRelative(timestamp, now))
    }

    @Test
    fun `formatRelative 3 days returns 3 days ago`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(3)
        assertEquals("3 days ago", DateUtils.formatRelative(timestamp, now))
    }

    @Test
    fun `formatRelative 7 days returns 1 week ago`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(7)
        assertEquals("1 week ago", DateUtils.formatRelative(timestamp, now))
    }

    @Test
    fun `formatRelative 14 days returns 2 weeks ago`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(14)
        assertEquals("2 weeks ago", DateUtils.formatRelative(timestamp, now))
    }

    @Test
    fun `formatRelative 60 days returns formatted date`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(60)
        val result = DateUtils.formatRelative(timestamp, now)
        assertTrue(result.isNotEmpty())
        assertTrue(result.contains("2023"))
    }

    @Test
    fun `formatHourMinute zero-padded`() {
        assertEquals("09:05", DateUtils.formatHourMinute(9, 5))
    }

    @Test
    fun `formatHourMinute no padding needed`() {
        assertEquals("14:30", DateUtils.formatHourMinute(14, 30))
    }

    @Test
    fun `formatDate returns non-empty string`() {
        val result = DateUtils.formatDate(now)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `formatTime returns non-empty string`() {
        val result = DateUtils.formatTime(now)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `formatMonthYear returns non-empty string`() {
        val result = DateUtils.formatMonthYear(now)
        assertTrue(result.isNotEmpty())
    }
}
