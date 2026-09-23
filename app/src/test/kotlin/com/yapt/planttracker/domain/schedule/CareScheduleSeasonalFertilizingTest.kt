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

    // --- Fix-round (#795): a raw date can land inside an active season while a gap opens up between
    // it and today that crosses into an inactive one — the due date must track *today's* season, not
    // just the raw date's own, or the plant reads overdue for the entire inactive stretch. ---

    @Test
    fun `raw date inside an active season is not due once the gap crosses into an inactive one`() {
        // Spring+Summer active; last fertilized Aug 20 (raw lands in the active run) but never
        // fertilized again — querying in October (Autumn, inactive) must not read this as overdue.
        val lastFertilized = seasonalFertilizingUtcMillis(2026, 8, 20)
        val now = seasonalFertilizingUtcMillis(2026, 10, 1)
        val status = CareSchedule.computeStatus(
            plant = plantWith(
                createdAt = lastFertilized - TimeUnit.DAYS.toMillis(100),
                activeSeasons = setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER)
            ),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilized,
            totalLogs = 1,
            now = now,
            hemisphere = Hemisphere.NORTHERN
        )

        assertEquals(utcStartOfDayMillis(2027, 3, 1), status.nextFertilizingDueAt)
        assertFalse(status.isFertilizingOverdue)
        assertFalse(status.isFertilizingDueSoon)
    }

    @Test
    fun `same plant queried on the first day back in an active season is due today, not overdue`() {
        val lastFertilized = seasonalFertilizingUtcMillis(2026, 8, 20)
        val now = seasonalFertilizingUtcMillis(2027, 3, 1)
        val status = CareSchedule.computeStatus(
            plant = plantWith(
                createdAt = lastFertilized - TimeUnit.DAYS.toMillis(100),
                activeSeasons = setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER)
            ),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilized,
            totalLogs = 1,
            now = now,
            hemisphere = Hemisphere.NORTHERN
        )

        assertEquals(utcStartOfDayMillis(2027, 3, 1), status.nextFertilizingDueAt)
        assertTrue(status.isFertilizingDueSoon)
        assertFalse(status.isFertilizingOverdue)
    }

    @Test
    fun `same plant queried days after re-entering the active season is overdue`() {
        val lastFertilized = seasonalFertilizingUtcMillis(2026, 8, 20)
        val now = seasonalFertilizingUtcMillis(2027, 3, 5)
        val status = CareSchedule.computeStatus(
            plant = plantWith(
                createdAt = lastFertilized - TimeUnit.DAYS.toMillis(100),
                activeSeasons = setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER)
            ),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilized,
            totalLogs = 1,
            now = now,
            hemisphere = Hemisphere.NORTHERN
        )

        assertEquals(utcStartOfDayMillis(2027, 3, 1), status.nextFertilizingDueAt)
        assertTrue(status.isFertilizingOverdue)
        assertFalse(status.isFertilizingDueSoon)
    }

    @Test
    fun `raw date already inside the current active run stays overdue from the raw date, unchanged`() {
        val lastFertilized = seasonalFertilizingUtcMillis(2026, 6, 20)
        val now = seasonalFertilizingUtcMillis(2026, 7, 25)
        val status = CareSchedule.computeStatus(
            plant = plantWith(
                createdAt = lastFertilized - TimeUnit.DAYS.toMillis(100),
                activeSeasons = setOf(FertilizingSeason.SUMMER)
            ),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilized,
            totalLogs = 1,
            now = now,
            hemisphere = Hemisphere.NORTHERN
        )

        assertEquals(lastFertilized + TimeUnit.DAYS.toMillis(30), status.nextFertilizingDueAt)
        assertTrue(status.isFertilizingOverdue)
    }

    @Test
    fun `all-active plant fertilized over a year ago stays overdue from the raw date`() {
        val lastFertilized = seasonalFertilizingUtcMillis(2026, 1, 15)
        val now = seasonalFertilizingUtcMillis(2027, 3, 1) // Well over 13 months after lastFertilized.
        val status = CareSchedule.computeStatus(
            plant = plantWith(createdAt = lastFertilized - TimeUnit.DAYS.toMillis(50)),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilized,
            totalLogs = 1,
            now = now,
            hemisphere = Hemisphere.NORTHERN
        )

        assertEquals(lastFertilized + TimeUnit.DAYS.toMillis(30), status.nextFertilizingDueAt)
        assertTrue(status.isFertilizingOverdue)
    }

    @Test
    fun `southern hemisphere variant is not due while the gap sits in an inactive season`() {
        val lastFertilized = seasonalFertilizingUtcMillis(2026, 8, 20) // Southern winter (active).
        val now = seasonalFertilizingUtcMillis(2026, 11, 1) // Southern spring (inactive).
        val status = CareSchedule.computeStatus(
            plant = plantWith(
                createdAt = lastFertilized - TimeUnit.DAYS.toMillis(100),
                activeSeasons = setOf(FertilizingSeason.AUTUMN, FertilizingSeason.WINTER)
            ),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilized,
            totalLogs = 1,
            now = now,
            hemisphere = Hemisphere.SOUTHERN
        )

        assertEquals(utcStartOfDayMillis(2027, 3, 1), status.nextFertilizingDueAt)
        assertFalse(status.isFertilizingOverdue)
        assertFalse(status.isFertilizingDueSoon)
    }

    @Test
    fun `year-wrapping active run is correctly identified as still containing the raw date`() {
        val lastFertilized = seasonalFertilizingUtcMillis(2026, 10, 1) // Inside the Sep-Feb wrapped run.
        val now = seasonalFertilizingUtcMillis(2027, 1, 10)
        val status = CareSchedule.computeStatus(
            plant = plantWith(
                createdAt = lastFertilized - TimeUnit.DAYS.toMillis(200),
                activeSeasons = setOf(FertilizingSeason.AUTUMN, FertilizingSeason.WINTER)
            ),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilized,
            totalLogs = 1,
            now = now,
            hemisphere = Hemisphere.NORTHERN
        )

        assertEquals(lastFertilized + TimeUnit.DAYS.toMillis(30), status.nextFertilizingDueAt)
        assertTrue(status.isFertilizingOverdue)
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
