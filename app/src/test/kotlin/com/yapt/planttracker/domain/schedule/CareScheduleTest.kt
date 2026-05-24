package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class CareScheduleTest {

    private val now = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun plantWith(
        wateringIntervalDays: Int? = null,
        fertilizingIntervalDays: Int? = null,
        wateringDueDateOverride: Long? = null
    ) = Plant(
        id = 1L,
        name = "Test Plant",
        wateringIntervalDays = wateringIntervalDays,
        fertilizingIntervalDays = fertilizingIntervalDays,
        wateringDueDateOverride = wateringDueDateOverride
    )

    @Test
    fun `no watering history returns null daysSince and nextDue and isOverdue false`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertNull(status.daysSinceLastWatering)
        assertNull(status.nextWateringDueAt)
        assertFalse(status.isOverdue)
    }

    @Test
    fun `nextWateringDueAt computed correctly`() {
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(3)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        val expected = lastWateredAt + TimeUnit.DAYS.toMillis(7)
        assertEquals(expected, status.nextWateringDueAt)
    }

    @Test
    fun `overdue when nextDue is in the past`() {
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(10)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertTrue(status.isOverdue)
    }

    @Test
    fun `isDueSoon when nextDue falls on same calendar day as now`() {
        // nextDueAt = now exactly → same calendar day
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(7)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertTrue(status.isDueSoon)
        assertFalse(status.isOverdue)
    }

    @Test
    fun `isDueSoon when nextDue is earlier same calendar day`() {
        // nextDueAt = 6h before now, still same UTC day (now is 22:13, nextDue = 16:13)
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(7) - TimeUnit.HOURS.toMillis(6)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertTrue(status.isDueSoon)
        assertFalse(status.isOverdue)
    }

    @Test
    fun `isOverdue when nextDue was yesterday even if less than 24h ago`() {
        // nextDueAt is 23h ago; with ms logic this was "due today", with day logic it is overdue
        // now = 22:13 UTC, nextDueAt = 23:13 UTC previous day (23h before now)
        val nextDueAt = now - TimeUnit.HOURS.toMillis(23)
        val lastWateredAt = nextDueAt - TimeUnit.DAYS.toMillis(7)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertTrue(status.isOverdue)
        assertFalse(status.isDueSoon)
    }

    @Test
    fun `not isDueSoon when nextDue is tomorrow`() {
        // nextDueAt = now + 2h → next UTC day (now is 22:13, +2h = 00:13 next day)
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(7) + TimeUnit.HOURS.toMillis(2)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertFalse(status.isDueSoon)
        assertFalse(status.isOverdue)
    }

    @Test
    fun `not overdue not due soon when next is more than one day away`() {
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(1)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertFalse(status.isOverdue)
        assertFalse(status.isDueSoon)
    }

    @Test
    fun `no interval set means isOverdue false even if watered long ago`() {
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(100)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = null),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertFalse(status.isOverdue)
    }

    @Test
    fun `daysSinceLastWatering is correct integer division`() {
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(5)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertEquals(5L, status.daysSinceLastWatering)
    }

    @Test
    fun `totalCareLogs passed through unchanged`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 42,
            now = now
        )
        assertEquals(42, status.totalCareLogs)
    }

    @Test
    fun `fertilizing overdue`() {
        val lastFertilizedAt = now - TimeUnit.DAYS.toMillis(15)
        val status = CareSchedule.computeStatus(
            plant = plantWith(fertilizingIntervalDays = 14),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilizedAt,
            totalLogs = 0,
            now = now
        )
        assertTrue(status.isFertilizingOverdue)
    }

    @Test
    fun `fertilizing isDueSoon when nextDue falls on same calendar day`() {
        val lastFertilizedAt = now - TimeUnit.DAYS.toMillis(14)
        val status = CareSchedule.computeStatus(
            plant = plantWith(fertilizingIntervalDays = 14),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilizedAt,
            totalLogs = 0,
            now = now
        )
        assertTrue(status.isFertilizingDueSoon)
        assertFalse(status.isFertilizingOverdue)
    }

    @Test
    fun `fertilizing isOverdue when nextDue was yesterday even if less than 24h ago`() {
        val nextFertDueAt = now - TimeUnit.HOURS.toMillis(23)
        val lastFertilizedAt = nextFertDueAt - TimeUnit.DAYS.toMillis(14)
        val status = CareSchedule.computeStatus(
            plant = plantWith(fertilizingIntervalDays = 14),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilizedAt,
            totalLogs = 0,
            now = now
        )
        assertTrue(status.isFertilizingOverdue)
        assertFalse(status.isFertilizingDueSoon)
    }

    @Test
    fun `no fertilizing interval means isFertilizingOverdue false`() {
        val lastFertilizedAt = now - TimeUnit.DAYS.toMillis(30)
        val status = CareSchedule.computeStatus(
            plant = plantWith(fertilizingIntervalDays = null),
            lastWateredAt = null,
            lastFertilizedAt = lastFertilizedAt,
            totalLogs = 0,
            now = now
        )
        assertFalse(status.isFertilizingOverdue)
    }

    @Test
    fun `TOO_LATE decreases interval by 1`() {
        assertEquals(6, CareSchedule.computeSuggestedInterval(WateringFeedback.TOO_LATE, 7))
    }

    @Test
    fun `JUST_RIGHT keeps interval unchanged`() {
        assertEquals(7, CareSchedule.computeSuggestedInterval(WateringFeedback.JUST_RIGHT, 7))
    }

    @Test
    fun `TOO_SOON increases interval by 1`() {
        assertEquals(8, CareSchedule.computeSuggestedInterval(WateringFeedback.TOO_SOON, 7))
    }

    @Test
    fun `TOO_LATE with actual=1 clamps to 1`() {
        assertEquals(1, CareSchedule.computeSuggestedInterval(WateringFeedback.TOO_LATE, 1))
    }

    @Test
    fun `daysBetween exactly one day`() {
        val earlier = now
        val later = now + TimeUnit.DAYS.toMillis(1)
        assertEquals(1, CareSchedule.daysBetween(earlier, later))
    }

    @Test
    fun `daysBetween same timestamp returns 0`() {
        assertEquals(0, CareSchedule.daysBetween(now, now))
    }

    @Test
    fun `daysBetween multiple days`() {
        val earlier = now
        val later = now + TimeUnit.DAYS.toMillis(5)
        assertEquals(5, CareSchedule.daysBetween(earlier, later))
    }

    @Test
    fun `daysBetween returns calendar days not truncated milliseconds`() {
        // 7 calendar days but only ~6.45 raw days — millisecond truncation would return 6
        val earlier = now
        val later = now + TimeUnit.DAYS.toMillis(7) - TimeUnit.HOURS.toMillis(13)
        assertEquals(7, CareSchedule.daysBetween(earlier, later))
    }

    @Test
    fun `JUST_RIGHT on due day produces no suggestion`() {
        // Watering on the due calendar day: actual == currentInterval → suggested == currentInterval,
        // so ViewModel suppresses the dialog.
        val earlier = now
        val later = now + TimeUnit.DAYS.toMillis(7) - TimeUnit.HOURS.toMillis(13)
        val actual = CareSchedule.daysBetween(earlier, later)
        val suggested = CareSchedule.computeSuggestedInterval(WateringFeedback.JUST_RIGHT, actual, 7)
        assertEquals(7, actual)
        assertEquals(7, suggested)
    }

    @Test
    fun `TOO_SOON with early watering extends beyond stored interval`() {
        // actual=7, stored=14 → user watered early; interval should grow past 14
        assertEquals(15, CareSchedule.computeSuggestedInterval(WateringFeedback.TOO_SOON, 7, 14))
    }

    @Test
    fun `TOO_SOON with on-schedule or late watering uses actual interval`() {
        // actual=9, stored=7 → user watered late; base is actual
        assertEquals(10, CareSchedule.computeSuggestedInterval(WateringFeedback.TOO_SOON, 9, 7))
    }

    @Test
    fun `TOO_SOON with no current interval uses actual interval`() {
        assertEquals(8, CareSchedule.computeSuggestedInterval(WateringFeedback.TOO_SOON, 7, null))
    }

    @Test
    fun `JUST_RIGHT with early watering returns actual interval`() {
        // actual=7, stored=14 → suggestion is 7 (ViewModel will surface this as a change)
        assertEquals(7, CareSchedule.computeSuggestedInterval(WateringFeedback.JUST_RIGHT, 7, 14))
    }

    @Test
    fun `TOO_LATE with actual greater than stored uses stored as base`() {
        // stored=14, actual=20 → user watered late; clamp base to stored so interval shrinks from 14
        assertEquals(13, CareSchedule.computeSuggestedInterval(WateringFeedback.TOO_LATE, 20, 14))
    }

    @Test
    fun `TOO_LATE with actual equal to stored decreases stored by 1`() {
        // stored=14, actual=14 → base is actual (== stored); result is 13
        assertEquals(13, CareSchedule.computeSuggestedInterval(WateringFeedback.TOO_LATE, 14, 14))
    }

    @Test
    fun `TOO_LATE with actual less than stored uses actual as base`() {
        // stored=14, actual=7 → user watered early but still too late; base is actual
        assertEquals(6, CareSchedule.computeSuggestedInterval(WateringFeedback.TOO_LATE, 7, 14))
    }

    @Test
    fun `TOO_LATE with no current interval falls back to actual minus 1`() {
        // currentIntervalDays=null → base is actualIntervalDays
        assertEquals(13, CareSchedule.computeSuggestedInterval(WateringFeedback.TOO_LATE, 14, null))
    }

    @Test
    fun `wateringDueDateOverride pushes effective due past computed due`() {
        // interval=7, watered 3 days ago → computed = now+4d; override = now+10d → effective = now+10d
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(3)
        val override = now + TimeUnit.DAYS.toMillis(10)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7, wateringDueDateOverride = override),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertEquals(override, status.nextWateringDueAt)
    }

    @Test
    fun `wateringDueDateOverride in the past is ignored when computed due is later`() {
        // interval=7, watered 1 day ago → computed = now+6d; override = now-1d → effective = now+6d
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(1)
        val override = now - TimeUnit.DAYS.toMillis(1)
        val computedDue = lastWateredAt + TimeUnit.DAYS.toMillis(7)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7, wateringDueDateOverride = override),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertEquals(computedDue, status.nextWateringDueAt)
    }
}
