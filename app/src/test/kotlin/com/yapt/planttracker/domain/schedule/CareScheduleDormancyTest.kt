package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringScheduleMode
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

    private fun dormantCadencePlant(
        cadence: Int = 35,
        wateringIntervalDays: Int? = 7
    ) = plantWith(wateringIntervalDays, 11, 2).copy(dormantWateringIntervalDays = cadence)

    @Test
    fun `dormant cadence uses latest watering plus fixed interval and ignores normal seasonal interval`() {
        val lastWatered = LocalDateUtcMillis(2022, 12, 20)
        val status = CareSchedule.computeStatus(
            plant = dormantCadencePlant(cadence = 35, wateringIntervalDays = 3).copy(
                wateringBaseIntervalDays = 100.0
            ),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now,
            seasonalAmplitude = 0.5
        )

        assertEquals(WateringScheduleMode.DORMANT_CADENCE, status.wateringScheduleMode)
        assertEquals(lastWatered + TimeUnit.DAYS.toMillis(35), status.computedNextWateringDueAt)
        assertEquals(status.computedNextWateringDueAt, status.dormantComputedNextWateringDueAt)
        assertFalse(status.isDueSoon)
        assertFalse(status.isOverdue)
    }

    @Test
    fun `dormant cadence floors a pre-window raw due at current wrapping cycle start`() {
        val status = CareSchedule.computeStatus(
            plant = dormantCadencePlant(28),
            lastWateredAt = LocalDateUtcMillis(2022, 1, 1),
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now
        )

        assertEquals(
            LocalDateUtcMillis(2022, 11, 1) - TimeUnit.HOURS.toMillis(12),
            status.computedNextWateringDueAt
        )
        assertTrue(status.isOverdue)
    }

    @Test
    fun `eight-week dormant-only cadence works without a normal watering interval`() {
        val status = CareSchedule.computeStatus(
            plant = dormantCadencePlant(cadence = 56, wateringIntervalDays = null),
            lastWateredAt = LocalDateUtcMillis(2022, 11, 20),
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now
        )

        assertEquals(WateringScheduleMode.DORMANT_CADENCE, status.wateringScheduleMode)
        assertEquals(LocalDateUtcMillis(2023, 1, 15), status.nextWateringDueAt)
        assertTrue(status.isDueSoon)
        assertEquals(null, status.normalComputedNextWateringDueAt)
    }

    @Test
    fun `dormant cadence remains calendar-correct on leap day`() {
        val leapDay = LocalDateUtcMillis(2024, 2, 29)
        val status = CareSchedule.computeStatus(
            plant = dormantCadencePlant(cadence = 28),
            lastWateredAt = LocalDateUtcMillis(2024, 2, 1),
            lastFertilizedAt = null,
            totalLogs = 1,
            now = leapDay
        )

        assertEquals(leapDay, status.nextWateringDueAt)
        assertTrue(status.isDueSoon)
    }

    @Test
    fun `never watered dormant cadence stays due today and override applies after active schedule`() {
        val futureOverride = LocalDateUtcMillis(2023, 1, 20)
        val status = CareSchedule.computeStatus(
            plant = dormantCadencePlant().copy(wateringDueDateOverride = futureOverride),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        assertEquals(now, status.computedNextWateringDueAt)
        assertEquals(futureOverride, status.nextWateringDueAt)
        assertFalse(status.isDueSoon)
    }

    @Test
    fun `unsupported cadence suspends and leaving dormancy immediately restores normal schedule`() {
        val lastWatered = LocalDateUtcMillis(2022, 12, 1)
        val malformed = CareSchedule.computeStatus(
            plant = dormantCadencePlant(cadence = 30),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now
        )
        val afterExit = CareSchedule.computeStatus(
            plant = dormantCadencePlant(cadence = 35),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = LocalDateUtcMillis(2023, 3, 1)
        )

        assertEquals(WateringScheduleMode.DORMANT_SUSPENDED, malformed.wateringScheduleMode)
        assertFalse(malformed.isOverdue)
        assertEquals(WateringScheduleMode.NORMAL, afterExit.wateringScheduleMode)
        assertEquals(lastWatered + TimeUnit.DAYS.toMillis(7), afterExit.nextWateringDueAt)
        assertTrue(afterExit.isOverdue)
    }

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
