package com.yapt.planttracker.ui.screens.plantdetail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Coverage for [rescheduledRelativeDueAt] — in particular #733 (technical ADR-0034): the +N day
 * calculation now routes through `Long.plusCalendarDays()` rather than a fixed
 * `TimeUnit.DAYS.toMillis(n)` span, so a +N reschedule spanning a DST fall-back day still advances the
 * local calendar date by the full N, not N minus one.
 */
class PlantDetailRescheduleActionsTest {

    private lateinit var originalTimeZone: TimeZone
    private val newYork = ZoneId.of("America/New_York")

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun `ordinary day advances by exactly N calendar days from the due-date anchor`() {
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
        val dueAt = LocalDateTime.of(2026, 6, 10, 9, 0).atZone(newYork).toInstant().toEpochMilli()
        val now = dueAt - TimeUnit.DAYS.toMillis(2)

        val result = rescheduledRelativeDueAt(dueAt, now, days = 3)

        assertEquals(LocalDate.of(2026, 6, 13), Instant.ofEpochMilli(result).atZone(newYork).toLocalDate())
    }

    @Test
    fun `+N across a DST fall-back day lands on due date plus N calendar days, not N days of fixed 24h`() {
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
        // Due date at 00:30 local on 2026-11-01, the fall-back day itself.
        val dueAt = LocalDateTime.of(2026, 11, 1, 0, 30).atZone(newYork).toInstant().toEpochMilli()
        val now = dueAt

        val result = rescheduledRelativeDueAt(dueAt, now, days = 1)

        val resultLocalDate = Instant.ofEpochMilli(result).atZone(newYork).toLocalDate()
        assertEquals(LocalDate.of(2026, 11, 2), resultLocalDate)

        // The old fixed-24h arithmetic would have landed back on Nov 1 (23:30 local) instead.
        val fixed24hResult = maxOf(dueAt, now) + TimeUnit.DAYS.toMillis(1)
        assertEquals(LocalDate.of(2026, 11, 1), Instant.ofEpochMilli(fixed24hResult).atZone(newYork).toLocalDate())
    }
}
