package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Regression coverage for #733 (technical ADR-0034): a fixed `+ TimeUnit.DAYS.toMillis(n)` advance
 * silently loses a day of local calendar-date advancement across a DST fall-back transition, because
 * a fall-back day is 25 real hours long. Pinned to `America/New_York` and a fixed fall-back instant
 * (2026-11-01 falls back 02:00 -> 01:00) rather than the rest of this file's UTC pin, since UTC has no
 * DST transitions to reproduce the bug against.
 */
class CareScheduleDstTest {

    private lateinit var originalTimeZone: TimeZone
    private val newYork = ZoneId.of("America/New_York")

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun plantWith(wateringIntervalDays: Int?, createdAt: Long) = Plant(
        id = 1L,
        name = "Test Plant",
        wateringIntervalDays = wateringIntervalDays,
        createdAt = createdAt
    )

    /**
     * Last watered at 00:30 local on the fall-back day itself, interval 1 day. The old
     * `lastWateredAt + TimeUnit.DAYS.toMillis(1)` arithmetic lands at 23:30 local on the *same* Nov 1
     * calendar day (a 25h day), which `dueStatusFor()`'s `toLocalDate()` comparison would then read as
     * still-Nov-1 — one day short of the intended Nov 2. The fixed `plusCalendarDays()` arithmetic lands
     * exactly on Nov 2 local, matching what every downstream consumer of `nextWateringDueAt` expects.
     */
    @Test
    fun `watering due date across a DST fall-back day lands on the correct calendar day`() {
        val lastWateredAt = LocalDateTime.of(2026, 11, 1, 0, 30)
            .atZone(newYork).toInstant().toEpochMilli()
        val now = lastWateredAt

        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 1, createdAt = lastWateredAt),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now
        )

        val dueDate = requireNotNull(status.nextWateringDueAt)
        val dueLocalDate = java.time.Instant.ofEpochMilli(dueDate).atZone(newYork).toLocalDate()
        assertEquals(java.time.LocalDate.of(2026, 11, 2), dueLocalDate)
    }
}
