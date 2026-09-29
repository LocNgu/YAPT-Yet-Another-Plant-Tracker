package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class CareScheduleSeasonalRepottingTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    private val spring = setOf(FertilizingSeason.SPRING)

    private fun startOfDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun noon(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun plant(
        createdAt: Long = noon(2025, 1, 1),
        repottingIntervalDays: Int? = null,
        repottingSeasons: Set<FertilizingSeason> = FertilizingSeason.entries.toSet(),
        repotPlanSeasonStartAt: Long? = null,
        repotPlanMadeAt: Long? = null
    ) = Plant(
        name = "Fern",
        createdAt = createdAt,
        repottingIntervalDays = repottingIntervalDays,
        repottingSeasons = repottingSeasons,
        repotPlanSeasonStartAt = repotPlanSeasonStartAt,
        repotPlanMadeAt = repotPlanMadeAt
    )

    private fun status(plant: Plant, now: Long, lastRepottedAt: Long? = null) = CareSchedule.computeStatus(
        plant = plant,
        lastWateredAt = null,
        lastFertilizedAt = null,
        totalLogs = 0,
        now = now,
        lastRepottedAt = lastRepottedAt,
        hemisphere = Hemisphere.NORTHERN
    )

    // --- unchanged behaviour with the new columns unset ---

    @Test
    fun `default plant keeps the plain interval date and reports no plan`() {
        val created = noon(2025, 6, 15)
        val status = status(plant(createdAt = created, repottingIntervalDays = 365), now = noon(2026, 1, 1))

        assertEquals(created + 365L * 24 * 60 * 60 * 1000, status.nextRepottingDueAt)
        assertNull(status.repottingPlanSeasonEndAt)
        assertFalse(status.isRepottingPlanned)
        assertFalse(status.isRepottingOverdue)
        assertFalse(status.isRepottingDueSoon)
    }

    @Test
    fun `every season preferred leaves an overdue interval date overdue`() {
        val lastRepotted = noon(2024, 1, 15)
        val status = status(
            plant(repottingIntervalDays = 365, repottingSeasons = FertilizingSeason.entries.toSet()),
            now = noon(2026, 9, 29),
            lastRepottedAt = lastRepotted
        )

        assertEquals(noon(2025, 1, 14), status.nextRepottingDueAt)
        assertTrue(status.isRepottingOverdue)
    }

    @Test
    fun `no interval and no plan leaves repotting unset`() {
        val status = status(plant(), now = noon(2026, 9, 29))

        assertNull(status.nextRepottingDueAt)
        assertNull(status.repottingPlanSeasonEndAt)
        assertFalse(status.isRepottingOverdue)
        assertFalse(status.isRepottingDueSoon)
    }

    // --- preferred seasons on the recurring interval ---

    @Test
    fun `interval date moves to the nearest preferred spring`() {
        val lastRepotted = noon(2025, 6, 15)
        val status = status(
            plant(repottingIntervalDays = 730, repottingSeasons = spring),
            now = noon(2026, 9, 29),
            lastRepottedAt = lastRepotted
        )

        assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
        assertFalse(status.isRepottingOverdue)
        assertFalse(status.isRepottingDueSoon)
        assertNull(status.repottingPlanSeasonEndAt)
    }

    @Test
    fun `a never-repotted plant's createdAt anchor goes through the same shift`() {
        val created = noon(2025, 6, 15)
        val status = status(
            plant(createdAt = created, repottingIntervalDays = 730, repottingSeasons = spring),
            now = noon(2026, 9, 29)
        )

        assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
    }

    @Test
    fun `a shifted interval date becomes due on its first day and overdue after it`() {
        val plant = plant(repottingIntervalDays = 730, repottingSeasons = spring)
        val lastRepotted = noon(2025, 6, 15)

        val onFirstDay = status(plant, now = noon(2027, 3, 1), lastRepottedAt = lastRepotted)
        val nextDay = status(plant, now = noon(2027, 3, 2), lastRepottedAt = lastRepotted)

        assertTrue(onFirstDay.isRepottingDueSoon)
        assertFalse(onFirstDay.isRepottingOverdue)
        assertTrue(nextDay.isRepottingOverdue)
        assertFalse(nextDay.isRepottingDueSoon)
    }

    @Test
    fun `a genuinely overdue interval date outside a preferred season waits for the next preferred stretch`() {
        val lastRepotted = noon(2024, 7, 1)
        val status = status(
            plant(repottingIntervalDays = 365, repottingSeasons = spring),
            now = noon(2026, 10, 5),
            lastRepottedAt = lastRepotted
        )

        assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
        assertFalse(status.isRepottingOverdue)
    }

    // --- one-off plan ---

    @Test
    fun `a plan stands alone with no repotting interval configured`() {
        val plant = plant(repotPlanSeasonStartAt = startOfDay(2027, 3, 1), repotPlanMadeAt = noon(2026, 9, 29))
        val status = status(plant, now = noon(2026, 9, 29))

        assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
        assertEquals(startOfDay(2027, 6, 1), status.repottingPlanSeasonEndAt)
        assertTrue(status.isRepottingPlanned)
        assertFalse(status.isRepottingDueSoon)
        assertFalse(status.isRepottingOverdue)
    }

    @Test
    fun `a plan is due for its whole season and never overdue while in season`() {
        val plant = plant(repotPlanSeasonStartAt = startOfDay(2027, 3, 1), repotPlanMadeAt = noon(2026, 9, 29))

        for (now in listOf(noon(2027, 3, 1), noon(2027, 4, 15), noon(2027, 5, 31))) {
            val status = status(plant, now = now)
            assertTrue(status.isRepottingDueSoon)
            assertFalse(status.isRepottingOverdue)
            assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
        }
    }

    @Test
    fun `a plan turns overdue the day after its season ends and stays overdue`() {
        val plant = plant(repotPlanSeasonStartAt = startOfDay(2027, 3, 1), repotPlanMadeAt = noon(2026, 9, 29))

        for (now in listOf(noon(2027, 6, 1), noon(2028, 2, 1))) {
            val status = status(plant, now = now)
            assertTrue(status.isRepottingOverdue)
            assertFalse(status.isRepottingDueSoon)
            assertEquals(startOfDay(2027, 6, 1), status.repottingPlanSeasonEndAt)
        }
    }

    @Test
    fun `a plan wins outright over an earlier interval date`() {
        val plant = plant(
            repottingIntervalDays = 365,
            repotPlanSeasonStartAt = startOfDay(2027, 3, 1),
            repotPlanMadeAt = noon(2026, 9, 29)
        )
        val status = status(plant, now = noon(2026, 9, 29), lastRepottedAt = noon(2025, 1, 1))

        assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
        assertFalse(status.isRepottingOverdue)
    }

    @Test
    fun `a plan wins outright over a later interval date`() {
        val plant = plant(
            repottingIntervalDays = 1000,
            repotPlanSeasonStartAt = startOfDay(2027, 3, 1),
            repotPlanMadeAt = noon(2026, 9, 29)
        )
        val status = status(plant, now = noon(2027, 3, 10), lastRepottedAt = noon(2026, 9, 1))

        assertEquals(startOfDay(2027, 3, 1), status.nextRepottingDueAt)
        assertTrue(status.isRepottingDueSoon)
    }

    @Test
    fun `clearing the plan restores the interval date`() {
        val withPlan = plant(
            repottingIntervalDays = 365,
            repotPlanSeasonStartAt = startOfDay(2027, 3, 1),
            repotPlanMadeAt = noon(2026, 9, 29)
        )
        val cleared = withPlan.copy(repotPlanSeasonStartAt = null, repotPlanMadeAt = null)
        val lastRepotted = noon(2026, 1, 1)

        val status = status(cleared, now = noon(2026, 9, 29), lastRepottedAt = lastRepotted)

        assertEquals(noon(2027, 1, 1), status.nextRepottingDueAt)
        assertNull(status.repottingPlanSeasonEndAt)
    }

    @Test
    fun `a plan ignores the preferred seasons entirely`() {
        val plant = plant(
            repottingIntervalDays = 365,
            repottingSeasons = spring,
            repotPlanSeasonStartAt = startOfDay(2026, 12, 1),
            repotPlanMadeAt = noon(2026, 9, 29)
        )
        val status = status(plant, now = noon(2026, 9, 29), lastRepottedAt = noon(2026, 1, 1))

        assertEquals(startOfDay(2026, 12, 1), status.nextRepottingDueAt)
        assertEquals(startOfDay(2027, 3, 1), status.repottingPlanSeasonEndAt)
    }
}
