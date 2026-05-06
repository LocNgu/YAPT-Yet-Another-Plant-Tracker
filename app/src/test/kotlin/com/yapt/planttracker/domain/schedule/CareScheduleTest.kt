package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CareScheduleTest {

    private val now = 1_700_000_000_000L

    private fun plantWith(
        wateringIntervalDays: Int? = null,
        fertilizingIntervalDays: Int? = null
    ) = Plant(
        id = 1L,
        name = "Test Plant",
        wateringIntervalDays = wateringIntervalDays,
        fertilizingIntervalDays = fertilizingIntervalDays
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
    fun `due soon within one day`() {
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(7) + TimeUnit.HOURS.toMillis(1)
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
    fun `fertilizing due soon`() {
        val lastFertilizedAt = now - TimeUnit.DAYS.toMillis(14) + TimeUnit.HOURS.toMillis(1)
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
}
