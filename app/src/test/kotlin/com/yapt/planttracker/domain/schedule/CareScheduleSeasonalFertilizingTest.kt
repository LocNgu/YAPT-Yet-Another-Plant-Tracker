package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class CareScheduleSeasonalFertilizingTest {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test
    fun `default plant with every season active is unchanged`() {
        val now = seasonalFertilizingUtcMillis(2026, 7, 15)
        val lastFertilized = now - TimeUnit.DAYS.toMillis(10)
        val status = CareSchedule.computeStatus(
            plant = plantWith(createdAt = now - TimeUnit.DAYS.toMillis(100)),
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
    fun `an inactive season is never due or overdue`() {
        val now = seasonalFertilizingUtcMillis(2026, 6, 15)
        val lastFertilized = now - TimeUnit.DAYS.toMillis(60)
        val status = CareSchedule.computeStatus(
            plant = plantWith(
                createdAt = now - TimeUnit.DAYS.toMillis(200),
                activeSeasons = setOf(FertilizingSeason.WINTER)
            ),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilized,
            totalLogs = 1,
            now = now,
            hemisphere = Hemisphere.NORTHERN
        )

        assertFalse(status.isFertilizingOverdue)
        assertFalse(status.isFertilizingDueSoon)
    }

    @Test
    fun `plant is due today on the first day of the next active season, not overdue from the old date`() {
        val now = seasonalFertilizingUtcMillis(2026, 9, 1)
        val lastFertilized = seasonalFertilizingUtcMillis(2026, 6, 1) // raw due = Jul 1 (Summer, inactive)
        val status = CareSchedule.computeStatus(
            plant = plantWith(
                createdAt = lastFertilized - TimeUnit.DAYS.toMillis(100),
                activeSeasons = setOf(FertilizingSeason.AUTUMN)
            ),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilized,
            totalLogs = 1,
            now = now,
            hemisphere = Hemisphere.NORTHERN
        )

        assertEquals(utcStartOfDayMillis(2026, 9, 1), status.nextFertilizingDueAt)
        assertTrue(status.isFertilizingDueSoon)
        assertFalse(status.isFertilizingOverdue)
    }

    @Test
    fun `first-fertilize grace date also shifts out of an inactive season`() {
        val createdAt = seasonalFertilizingUtcMillis(2026, 6, 1) // grace date = Jul 1 (Summer, inactive)
        val now = createdAt + TimeUnit.DAYS.toMillis(5)
        val status = CareSchedule.computeStatus(
            plant = plantWith(createdAt = createdAt, activeSeasons = setOf(FertilizingSeason.AUTUMN)),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now,
            hemisphere = Hemisphere.NORTHERN
        )

        assertEquals(utcStartOfDayMillis(2026, 9, 1), status.nextFertilizingDueAt)
        assertFalse(status.isFertilizingDueSoon)
        assertFalse(status.isFertilizingOverdue)
    }

    @Test
    fun `southern hemisphere maps the active season using the opposite mapping`() {
        val now = seasonalFertilizingUtcMillis(2026, 12, 15) // Southern summer.
        val lastFertilized = now - TimeUnit.DAYS.toMillis(10)
        val status = CareSchedule.computeStatus(
            plant = plantWith(
                createdAt = now - TimeUnit.DAYS.toMillis(200),
                activeSeasons = setOf(FertilizingSeason.SUMMER)
            ),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilized,
            totalLogs = 1,
            now = now,
            hemisphere = Hemisphere.SOUTHERN
        )

        assertEquals(lastFertilized + TimeUnit.DAYS.toMillis(30), status.nextFertilizingDueAt)
        assertFalse(status.isFertilizingOverdue)
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

    private fun plantWith(
        createdAt: Long,
        activeSeasons: Set<FertilizingSeason> = FertilizingSeason.entries.toSet()
    ) = Plant(
        name = "Fern",
        createdAt = createdAt,
        fertilizingIntervalDays = 30,
        fertilizingSeasons = activeSeasons
    )
}

@Suppress("FunctionNaming")
private fun seasonalFertilizingUtcMillis(year: Int, month: Int, day: Int): Long {
    val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    calendar.clear()
    calendar.set(year, month - 1, day, 12, 0, 0)
    return calendar.timeInMillis
}

/** Midnight (not noon), matching [SeasonalFertilizing.nextActiveDueAtMillis]'s shifted-date output. */
private fun utcStartOfDayMillis(year: Int, month: Int, day: Int): Long =
    LocalDate.of(year, month, day).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
