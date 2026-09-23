package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class CareScheduleSeasonalFertilizingTest {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test
    fun `current season interval drives the due date`() {
        val now = seasonalFertilizingUtcMillis(2026, 7, 15)
        val lastFertilized = now - TimeUnit.DAYS.toMillis(10)
        val plant = plantWith(
            createdAt = now - TimeUnit.DAYS.toMillis(100),
            fertilizingIntervalSummer = 14
        )

        val status = CareSchedule.computeStatus(
            plant = plant,
            lastWateredAt = null,
            lastFertilizedAt = lastFertilized,
            totalLogs = 1,
            now = now,
            hemisphere = Hemisphere.NORTHERN
        )

        assertEquals(lastFertilized + TimeUnit.DAYS.toMillis(14), status.nextFertilizingDueAt)
        assertFalse(status.isFertilizingOverdue)
    }

    @Test
    fun `southern December uses the summer interval`() {
        val now = seasonalFertilizingUtcMillis(2026, 12, 15)
        val lastFertilized = now - TimeUnit.DAYS.toMillis(20)
        val status = CareSchedule.computeStatus(
            plant = plantWith(createdAt = now, fertilizingIntervalSummer = 14),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilized,
            totalLogs = 1,
            now = now,
            hemisphere = Hemisphere.SOUTHERN
        )

        assertEquals(lastFertilized + TimeUnit.DAYS.toMillis(14), status.nextFertilizingDueAt)
        assertTrue(status.isFertilizingOverdue)
    }

    @Test
    fun `null season slot keeps the existing main interval behavior`() {
        val now = seasonalFertilizingUtcMillis(2026, 4, 15)
        val lastFertilized = now - TimeUnit.DAYS.toMillis(20)
        val status = CareSchedule.computeStatus(
            plant = plantWith(createdAt = now),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilized,
            totalLogs = 1,
            now = now,
            hemisphere = Hemisphere.NORTHERN
        )

        assertEquals(lastFertilized + TimeUnit.DAYS.toMillis(30), status.nextFertilizingDueAt)
        assertFalse(status.isFertilizingOverdue)
    }

    @Test
    fun `never fertilized plant keeps the first-fertilize grace period`() {
        val createdAt = seasonalFertilizingUtcMillis(2026, 6, 1)
        val now = createdAt + TimeUnit.DAYS.toMillis(5)
        val status = CareSchedule.computeStatus(
            plant = plantWith(createdAt = createdAt, fertilizingIntervalSummer = 1),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now,
            hemisphere = Hemisphere.NORTHERN
        )

        assertEquals(
            createdAt + TimeUnit.DAYS.toMillis(CareSchedule.FIRST_FERTILIZE_GRACE_DAYS.toLong()),
            status.nextFertilizingDueAt
        )
        assertFalse(status.isFertilizingDueSoon)
    }

    @Test
    fun `shared dormancy window suppresses fertilizing flags without moving the due date`() {
        val now = seasonalFertilizingUtcMillis(2026, 1, 15)
        val lastFertilized = now - TimeUnit.DAYS.toMillis(60)
        val basePlant = plantWith(createdAt = now - TimeUnit.DAYS.toMillis(100))
        val active = CareSchedule.computeStatus(basePlant, null, lastFertilized, 1, now)
        val dormant = CareSchedule.computeStatus(
            basePlant.copy(dormancyStartMonth = 11, dormancyEndMonth = 2),
            null,
            lastFertilized,
            1,
            now
        )

        assertTrue(active.isFertilizingOverdue)
        assertFalse(dormant.isFertilizingOverdue)
        assertFalse(dormant.isFertilizingDueSoon)
        assertEquals(active.nextFertilizingDueAt, dormant.nextFertilizingDueAt)
    }

    private fun plantWith(createdAt: Long, fertilizingIntervalSummer: Int? = null) = Plant(
        name = "Fern",
        createdAt = createdAt,
        fertilizingIntervalDays = 30,
        fertilizingIntervalSummer = fertilizingIntervalSummer
    )
}

@Suppress("FunctionNaming")
private fun seasonalFertilizingUtcMillis(year: Int, month: Int, day: Int): Long {
    val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    calendar.clear()
    calendar.set(year, month - 1, day, 12, 0, 0)
    return calendar.timeInMillis
}
