package com.yapt.planttracker.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

// Robolectric: localized date patterns come from android.text.format.DateFormat (#1005).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
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
    fun `relativeDate 0 days returns Today`() {
        val timestamp = now - TimeUnit.HOURS.toMillis(1)
        assertEquals(DateUtils.RelativeDate.Today, DateUtils.relativeDate(timestamp, now))
    }

    @Test
    fun `relativeDate 1 day returns Yesterday`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(1)
        assertEquals(DateUtils.RelativeDate.Yesterday, DateUtils.relativeDate(timestamp, now))
    }

    @Test
    fun `relativeDate 3 days returns 3 days ago`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(3)
        assertEquals(DateUtils.RelativeDate.DaysAgo(3), DateUtils.relativeDate(timestamp, now))
    }

    @Test
    fun `relativeDate 7 days returns 7 days ago`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(7)
        assertEquals(DateUtils.RelativeDate.DaysAgo(7), DateUtils.relativeDate(timestamp, now))
    }

    @Test
    fun `relativeDate 30 days with default returns relative`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(30)
        assertEquals(DateUtils.RelativeDate.DaysAgo(30), DateUtils.relativeDate(timestamp, now))
    }

    @Test
    fun `relativeDate maxRelativeDays 14, 14 days returns relative`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(14)
        assertEquals(DateUtils.RelativeDate.DaysAgo(14), DateUtils.relativeDate(timestamp, now, 14))
    }

    @Test
    fun `relativeDate maxRelativeDays 14, 15 days returns exact date`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(15)
        assertEquals(DateUtils.RelativeDate.ExactDate("Oct 30, 2023"), DateUtils.relativeDate(timestamp, now, 14))
    }

    @Test
    fun `relativeDate maxRelativeDays 14, 7 days returns relative`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(7)
        assertEquals(DateUtils.RelativeDate.DaysAgo(7), DateUtils.relativeDate(timestamp, now, 14))
    }

    @Test
    fun `relativeDate 60 days with default returns relative`() {
        val timestamp = now - TimeUnit.DAYS.toMillis(60)
        assertEquals(DateUtils.RelativeDate.DaysAgo(60), DateUtils.relativeDate(timestamp, now))
    }

    @Test
    fun `relativeDate same calendar day but over 1h ago returns Today`() {
        // now = 2023-11-14 22:13 UTC; 6h ago = 16:13 same day
        val timestamp = now - TimeUnit.HOURS.toMillis(6)
        assertEquals(DateUtils.RelativeDate.Today, DateUtils.relativeDate(timestamp, now))
    }

    @Test
    fun `relativeDate previous calendar day but less than 24h ago returns Yesterday`() {
        // now = 2023-11-14 22:13 UTC; 23h ago = 2023-11-13 23:13 — different calendar day
        val timestamp = now - TimeUnit.HOURS.toMillis(23)
        assertEquals(DateUtils.RelativeDate.Yesterday, DateUtils.relativeDate(timestamp, now))
    }

    @Test
    fun `relativeDate next calendar day returns Tomorrow`() {
        val timestamp = now + TimeUnit.HOURS.toMillis(2)
        assertEquals(DateUtils.RelativeDate.Tomorrow, DateUtils.relativeDate(timestamp, now))
    }

    @Test
    fun `relativeDate multiple future calendar days returns In days`() {
        val timestamp = now + TimeUnit.DAYS.toMillis(3)
        assertEquals(DateUtils.RelativeDate.InDays(3), DateUtils.relativeDate(timestamp, now))
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

    // countdown

    @Test
    fun `countdown due later same calendar day returns DueToday`() {
        // now = 22:13 UTC, +1h = 23:13 UTC — still same calendar day
        val dueAt = now + TimeUnit.HOURS.toMillis(1)
        assertEquals(DateUtils.Countdown.DueToday, DateUtils.countdown(dueAt, now))
    }

    @Test
    fun `countdown due exactly now returns DueToday`() {
        assertEquals(DateUtils.Countdown.DueToday, DateUtils.countdown(now, now))
    }

    @Test
    fun `countdown overdue earlier same calendar day returns DueToday`() {
        // now = 22:13 UTC, -6h = 16:13 UTC — still same calendar day
        val dueAt = now - TimeUnit.HOURS.toMillis(6)
        assertEquals(DateUtils.Countdown.DueToday, DateUtils.countdown(dueAt, now))
    }

    @Test
    fun `countdown overdue on previous calendar day returns Overdue even if less than 24h ago`() {
        // now = 22:13 UTC, -23h = 23:13 UTC previous day — different calendar day
        val dueAt = now - TimeUnit.HOURS.toMillis(23)
        assertEquals(DateUtils.Countdown.Overdue(1), DateUtils.countdown(dueAt, now))
    }

    @Test
    fun `countdown overdue exactly 1 day returns singular`() {
        val dueAt = now - TimeUnit.DAYS.toMillis(1)
        assertEquals(DateUtils.Countdown.Overdue(1), DateUtils.countdown(dueAt, now))
    }

    @Test
    fun `countdown overdue multiple days returns plural`() {
        val dueAt = now - TimeUnit.DAYS.toMillis(5)
        assertEquals(DateUtils.Countdown.Overdue(5), DateUtils.countdown(dueAt, now))
    }

    @Test
    fun `countdown due tomorrow returns In 1 day`() {
        // now = 22:13 UTC, +2h = 00:13 UTC next day
        val dueAt = now + TimeUnit.HOURS.toMillis(2)
        assertEquals(DateUtils.Countdown.InDays(1), DateUtils.countdown(dueAt, now))
    }

    @Test
    fun `countdown due in exactly 1 day returns singular`() {
        val dueAt = now + TimeUnit.DAYS.toMillis(1)
        assertEquals(DateUtils.Countdown.InDays(1), DateUtils.countdown(dueAt, now))
    }

    @Test
    fun `countdown due in multiple days returns plural`() {
        val dueAt = now + TimeUnit.DAYS.toMillis(7)
        assertEquals(DateUtils.Countdown.InDays(7), DateUtils.countdown(dueAt, now))
    }

    // formatWeekdayDate

    @Test
    fun `formatWeekdayDate returns weekday and date`() {
        val epochDay = LocalDate.of(2023, 11, 14).toEpochDay()
        assertEquals("Tue, Nov 14", DateUtils.formatWeekdayDate(epochDay))
    }

    @Test
    fun `formatDate follows the German field order`() {
        Locale.setDefault(Locale.GERMANY)
        val timestamp = LocalDate.of(2023, 10, 30).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("30. Okt. 2023", DateUtils.formatDate(timestamp))
    }

    @Test
    fun `formatWeekdayDate follows the German field order`() {
        Locale.setDefault(Locale.GERMANY)
        val epochDay = LocalDate.of(2023, 11, 14).toEpochDay()
        assertEquals("Di., 14. Nov.", DateUtils.formatWeekdayDate(epochDay))
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

    // plusCalendarDays (#733, technical ADR-0034)

    private val newYork = java.time.ZoneId.of("America/New_York")

    @Test
    fun `plusCalendarDays across a DST fall-back day lands on local date plus n, not 24h later`() {
        // America/New_York falls back 02:00 -> 01:00 on 2026-11-01. 00:30 local on Nov 1 is a
        // genuinely 25h calendar day; a fixed +24h would land at 23:30 local on Nov 1 (same date),
        // one day short of what every due-date consumer expects.
        val nov1At0030 = java.time.LocalDateTime.of(2026, 11, 1, 0, 30)
            .atZone(newYork).toInstant().toEpochMilli()

        val result = nov1At0030.plusCalendarDays(1, newYork)

        val resultZoned = java.time.Instant.ofEpochMilli(result).atZone(newYork)
        assertEquals(LocalDate.of(2026, 11, 2), resultZoned.toLocalDate())
        assertEquals(0, resultZoned.hour)
        assertEquals(30, resultZoned.minute)
    }

    @Test
    fun `plusCalendarDays across a DST fall-back day is NOT the fixed 24h result`() {
        val nov1At0030 = java.time.LocalDateTime.of(2026, 11, 1, 0, 30)
            .atZone(newYork).toInstant().toEpochMilli()
        val fixed24hResult = nov1At0030 + TimeUnit.DAYS.toMillis(1)

        val result = nov1At0030.plusCalendarDays(1, newYork)

        assertTrue(result != fixed24hResult)
        // The fixed-24h arithmetic lands back on Nov 1 (23:30 local) — the exact bug this fixes.
        val fixedZoned = java.time.Instant.ofEpochMilli(fixed24hResult).atZone(newYork)
        assertEquals(LocalDate.of(2026, 11, 1), fixedZoned.toLocalDate())
    }

    @Test
    fun `plusCalendarDays across a DST spring-forward day still advances the calendar date`() {
        // America/New_York springs forward 02:00 -> 03:00 on 2026-03-08 (a 23h calendar day).
        val mar8At0030 = java.time.LocalDateTime.of(2026, 3, 8, 0, 30)
            .atZone(newYork).toInstant().toEpochMilli()

        val result = mar8At0030.plusCalendarDays(1, newYork)

        val resultZoned = java.time.Instant.ofEpochMilli(result).atZone(newYork)
        assertEquals(LocalDate.of(2026, 3, 9), resultZoned.toLocalDate())
        assertEquals(0, resultZoned.hour)
        assertEquals(30, resultZoned.minute)
    }

    @Test
    fun `plusCalendarDays on an ordinary day equals a fixed 24h advance`() {
        val ordinaryDay = java.time.LocalDateTime.of(2026, 6, 15, 14, 45)
            .atZone(newYork).toInstant().toEpochMilli()

        val result = ordinaryDay.plusCalendarDays(1, newYork)

        assertEquals(ordinaryDay + TimeUnit.DAYS.toMillis(1), result)
    }

    @Test
    fun `plusCalendarDays preserves local time-of-day`() {
        val someInstant = java.time.LocalDateTime.of(2026, 6, 15, 14, 45, 30)
            .atZone(newYork).toInstant().toEpochMilli()

        val result = someInstant.plusCalendarDays(5, newYork)

        val resultZoned = java.time.Instant.ofEpochMilli(result).atZone(newYork)
        assertEquals(14, resultZoned.hour)
        assertEquals(45, resultZoned.minute)
        assertEquals(30, resultZoned.second)
        assertEquals(LocalDate.of(2026, 6, 20), resultZoned.toLocalDate())
    }

    @Test
    fun `plusCalendarDays defaults to the system default zone`() {
        val timestamp = now
        assertEquals(timestamp.plusCalendarDays(3, java.time.ZoneId.systemDefault()), timestamp.plusCalendarDays(3))
    }

    @Test
    fun `toNoonMillis is noon of the local day, also on a DST change day`() {
        assertEquals(
            LocalDate.of(2023, 11, 14).toStartOfDayMillis() + TimeUnit.HOURS.toMillis(12),
            LocalDate.of(2023, 11, 14).toNoonMillis()
        )

        // 2023-11-05 is 25 hours long in New York: noon is 13 hours after local midnight there.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val fallBack = LocalDate.of(2023, 11, 5)
        assertEquals(fallBack, fallBack.toNoonMillis().toLocalDate())
        assertEquals(TimeUnit.HOURS.toMillis(13), fallBack.toNoonMillis() - fallBack.toStartOfDayMillis())
    }
}
