package com.yapt.planttracker.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class DateUtilsTest {

    private val now = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC

    private lateinit var originalTimeZone: TimeZone
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        // Pin timezone and locale so calendar-day math and English month/weekday
        // abbreviations (e.g. "Tue, Nov 14") are stable regardless of the machine's
        // defaults. Production DateUtils intentionally stays locale-aware — this pin
        // is test-only.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
    }

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
    fun `formatRelative 7 days returns 7 days ago`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(7)
        assertEquals("7 days ago", DateUtils.formatRelative(timestamp, now))
    }

    @Test
    fun `formatRelative 30 days with default returns relative`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(30)
        assertEquals("30 days ago", DateUtils.formatRelative(timestamp, now))
    }

    @Test
    fun `formatRelative maxRelativeDays 14, 14 days returns relative`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(14)
        assertEquals("14 days ago", DateUtils.formatRelative(timestamp, now, maxRelativeDays = 14))
    }

    @Test
    fun `formatRelative maxRelativeDays 14, 15 days returns exact date`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(15)
        assertEquals("Oct 30, 2023", DateUtils.formatRelative(timestamp, now, maxRelativeDays = 14))
    }

    @Test
    fun `formatRelative maxRelativeDays 14, 7 days returns relative`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(7)
        assertEquals("7 days ago", DateUtils.formatRelative(timestamp, now, maxRelativeDays = 14))
    }

    @Test
    fun `formatRelative 60 days with default returns relative`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(60)
        assertEquals("60 days ago", DateUtils.formatRelative(timestamp, now))
    }

    @Test
    fun `formatRelative same calendar day but over 1h ago returns Today`() {
        // now = 2023-11-14 22:13 UTC; 6h ago = 16:13 same day
        val timestamp = now - TimeUnit.HOURS.toMillis(6)
        assertEquals("Today", DateUtils.formatRelative(timestamp, now))
    }

    @Test
    fun `formatRelative previous calendar day but less than 24h ago returns Yesterday`() {
        // now = 2023-11-14 22:13 UTC; 23h ago = 2023-11-13 23:13 — different calendar day
        val timestamp = now - TimeUnit.HOURS.toMillis(23)
        assertEquals("Yesterday", DateUtils.formatRelative(timestamp, now))
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

    // formatCountdown

    @Test
    fun `formatCountdown due later same calendar day returns Due today`() {
        // now = 22:13 UTC, +1h = 23:13 UTC — still same calendar day
        val dueAt = now + TimeUnit.HOURS.toMillis(1)
        assertEquals("Due today", DateUtils.formatCountdown(dueAt, now))
    }

    @Test
    fun `formatCountdown due exactly now returns Due today`() {
        assertEquals("Due today", DateUtils.formatCountdown(now, now))
    }

    @Test
    fun `formatCountdown overdue earlier same calendar day returns Due today`() {
        // now = 22:13 UTC, -6h = 16:13 UTC — still same calendar day
        val dueAt = now - TimeUnit.HOURS.toMillis(6)
        assertEquals("Due today", DateUtils.formatCountdown(dueAt, now))
    }

    @Test
    fun `formatCountdown overdue on previous calendar day returns Overdue even if less than 24h ago`() {
        // now = 22:13 UTC, -23h = 23:13 UTC previous day — different calendar day
        val dueAt = now - TimeUnit.HOURS.toMillis(23)
        assertEquals("Overdue by 1 day", DateUtils.formatCountdown(dueAt, now))
    }

    @Test
    fun `formatCountdown overdue exactly 1 day returns singular`() {
        val dueAt = now - TimeUnit.DAYS.toMillis(1)
        assertEquals("Overdue by 1 day", DateUtils.formatCountdown(dueAt, now))
    }

    @Test
    fun `formatCountdown overdue multiple days returns plural`() {
        val dueAt = now - TimeUnit.DAYS.toMillis(5)
        assertEquals("Overdue by 5 days", DateUtils.formatCountdown(dueAt, now))
    }

    @Test
    fun `formatCountdown due tomorrow returns In 1 day`() {
        // now = 22:13 UTC, +2h = 00:13 UTC next day
        val dueAt = now + TimeUnit.HOURS.toMillis(2)
        assertEquals("In 1 day", DateUtils.formatCountdown(dueAt, now))
    }

    @Test
    fun `formatCountdown due in exactly 1 day returns singular`() {
        val dueAt = now + TimeUnit.DAYS.toMillis(1)
        assertEquals("In 1 day", DateUtils.formatCountdown(dueAt, now))
    }

    @Test
    fun `formatCountdown due in multiple days returns plural`() {
        val dueAt = now + TimeUnit.DAYS.toMillis(7)
        assertEquals("In 7 days", DateUtils.formatCountdown(dueAt, now))
    }

    // formatWeekdayDate

    @Test
    fun `formatWeekdayDate returns weekday and date`() {
        val epochDay = LocalDate.of(2023, 11, 14).toEpochDay()
        assertEquals("Tue, Nov 14", DateUtils.formatWeekdayDate(epochDay))
    }

    // todayRangeMillis

    @Test
    fun `todayRangeMillis returns midnight-to-midnight window containing now`() {
        val (start, end) = DateUtils.todayRangeMillis(now)
        val startOfDay = LocalDate.of(2023, 11, 14).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        assertEquals(startOfDay, start)
        assertEquals(startOfDay + TimeUnit.DAYS.toMillis(1), end)
        assertTrue(now >= start && now < end)
        // Window spans exactly one day: [start, end).
        assertEquals(TimeUnit.DAYS.toMillis(1), end - start)
    }
}
