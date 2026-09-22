package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Wiring tests for #699/#760 (product ADR-0044) — the pure predicate itself is covered by [DormancyWindowTest]. */
class CareScheduleDormancyTest {

    // Jan 15 2023 12:00 UTC — inside a Nov-Feb wrapping window, outside a Jun-Aug one.
    private val now = LocalDateUtcMillis(2023, 1, 15)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun plantWith(
        wateringIntervalDays: Int? = 7,
        dormancyStartMonth: Int? = null,
        dormancyEndMonth: Int? = null
    ) = Plant(
        id = 1L,
        name = "Test Plant",
        wateringIntervalDays = wateringIntervalDays,
        createdAt = now,
        dormancyStartMonth = dormancyStartMonth,
        dormancyEndMonth = dormancyEndMonth
    )

    @Test
    fun `a wildly overdue plant inside its dormancy window reports not overdue and not due soon`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(100)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7, dormancyStartMonth = 11, dormancyEndMonth = 2),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        assertTrue(status.isDormant)
        assertFalse(status.isOverdue)
        assertFalse(status.isDueSoon)
    }

    @Test
    fun `dormancy does not push nextWateringDueAt to the window's end`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(100)
        val dormant = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7, dormancyStartMonth = 11, dormancyEndMonth = 2),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        val notDormant = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        // The due date is computed identically either way — only isOverdue/isDueSoon differ.
        assertEquals(notDormant.nextWateringDueAt, dormant.nextWateringDueAt)
        assertEquals(lastWatered + TimeUnit.DAYS.toMillis(7), dormant.nextWateringDueAt)
    }

    @Test
    fun `a never-watered plant due today inside its dormancy window reports not due soon`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7, dormancyStartMonth = 11, dormancyEndMonth = 2),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        assertTrue(status.isDormant)
        assertFalse(status.isDueSoon)
        assertFalse(status.isOverdue)
    }

    @Test
    fun `a plant outside its dormancy window is unaffected`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(100)
        val status = CareSchedule.computeStatus(
            // Now (Jan 15) is outside a Jun-Aug summer-dormant window.
            plant = plantWith(wateringIntervalDays = 7, dormancyStartMonth = 6, dormancyEndMonth = 8),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        assertFalse(status.isDormant)
        assertTrue(status.isOverdue)
    }

    @Test
    fun `a plant with no dormancy window is bit-for-bit unchanged`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(100)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        assertFalse(status.isDormant)
        assertTrue(status.isOverdue)
        assertFalse(status.isDueSoon)
        assertEquals(lastWatered + TimeUnit.DAYS.toMillis(7), status.nextWateringDueAt)
    }

    // ---- isWateringGapDormancySpanning (#761) — distinct from isDormant, see PlantCareStatus's doc ----

    @Test
    fun `a gap that crosses into the dormancy window reports gap-dormancy-spanning true`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(100)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7, dormancyStartMonth = 11, dormancyEndMonth = 2),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        assertTrue(status.isWateringGapDormancySpanning)
    }

    @Test
    fun `a gap that never touches the dormancy window reports gap-dormancy-spanning false`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(5)
        val status = CareSchedule.computeStatus(
            // Jun-Aug window has nothing to do with a 5-day-old January gap.
            plant = plantWith(wateringIntervalDays = 7, dormancyStartMonth = 6, dormancyEndMonth = 8),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        assertFalse(status.isWateringGapDormancySpanning)
    }

    @Test
    fun `no prior watering at all reports gap-dormancy-spanning false`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7, dormancyStartMonth = 11, dormancyEndMonth = 2),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        assertFalse(status.isWateringGapDormancySpanning)
    }

    @Test
    fun `exiting the dormancy window on the very next day restores overdue reporting`() {
        // Nov 1 - Jan 31 window; Feb 1 is the first day outside it.
        val febFirst = LocalDateUtcMillis(2023, 2, 1)
        val lastWatered = febFirst - TimeUnit.DAYS.toMillis(100)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7, dormancyStartMonth = 11, dormancyEndMonth = 1),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = febFirst
        )

        assertFalse(status.isDormant)
        assertTrue(status.isOverdue)
    }
}

@Suppress("FunctionNaming")
private fun LocalDateUtcMillis(year: Int, month: Int, day: Int): Long {
    val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day, 12, 0, 0)
    return cal.timeInMillis
}
