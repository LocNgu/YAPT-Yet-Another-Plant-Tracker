package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
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
        wateringDueDateOverride: Long? = null,
        createdAt: Long = now,
        repottingIntervalDays: Int? = null
    ) = Plant(
        id = 1L,
        name = "Test Plant",
        wateringIntervalDays = wateringIntervalDays,
        fertilizingIntervalDays = fertilizingIntervalDays,
        wateringDueDateOverride = wateringDueDateOverride,
        createdAt = createdAt,
        repottingIntervalDays = repottingIntervalDays
    )

    @Test
    fun `no watering history with interval set is due today`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertNull(status.daysSinceLastWatering)
        assertEquals(now, status.nextWateringDueAt)
        assertTrue(status.isDueSoon)
        assertFalse(status.isOverdue)
    }

    @Test
    fun `no watering history with interval set and future override uses override`() {
        val override = now + TimeUnit.DAYS.toMillis(5)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7, wateringDueDateOverride = override),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertEquals(override, status.nextWateringDueAt)
        assertFalse(status.isDueSoon)
        assertFalse(status.isOverdue)
        // #630: computedNextDueAt was `now` (no watering history) — the override is 5 days beyond it.
        assertEquals(5, status.rescheduleDeltaDays)
    }

    @Test
    fun `no watering history with interval set and expired past override still due today`() {
        // maxOf(computedNextDueAt=now, override) picks `now` since the override is stale
        val override = now - TimeUnit.DAYS.toMillis(5)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7, wateringDueDateOverride = override),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertEquals(now, status.nextWateringDueAt)
        assertTrue(status.isDueSoon)
        assertFalse(status.isOverdue)
        // #630: a stale, non-winning override reports no delta — nothing to explain or revert.
        assertNull(status.rescheduleDeltaDays)
    }

    // ---- rescheduleDeltaDays (#630) ----

    @Test
    fun `rescheduleDeltaDays is null when there is no override`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = now - TimeUnit.DAYS.toMillis(3),
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertNull(status.rescheduleDeltaDays)
    }

    @Test
    fun `rescheduleDeltaDays is the gap in days between the computed due date and a winning override`() {
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(3)
        val computedNextDueAt = lastWateredAt + TimeUnit.DAYS.toMillis(7)
        val override = computedNextDueAt + TimeUnit.DAYS.toMillis(4)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7, wateringDueDateOverride = override),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertEquals(override, status.nextWateringDueAt)
        assertEquals(4, status.rescheduleDeltaDays)
    }

    @Test
    fun `rescheduleDeltaDays stays populated once overdue against a winning override`() {
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(30)
        val computedNextDueAt = lastWateredAt + TimeUnit.DAYS.toMillis(7)
        val override = computedNextDueAt + TimeUnit.DAYS.toMillis(2)
        val overdueNow = override + TimeUnit.DAYS.toMillis(10)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7, wateringDueDateOverride = override),
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = overdueNow
        )
        assertTrue(status.isOverdue)
        assertEquals(2, status.rescheduleDeltaDays)
    }

    @Test
    fun `no watering history and no interval set stays not scheduled`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = null),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertNull(status.daysSinceLastWatering)
        assertNull(status.nextWateringDueAt)
        assertFalse(status.isOverdue)
        assertFalse(status.isDueSoon)
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
    fun `never fertilized with recent createdAt is not yet due`() {
        val createdAt = now - TimeUnit.DAYS.toMillis(5)
        val status = CareSchedule.computeStatus(
            plant = plantWith(fertilizingIntervalDays = 14, createdAt = createdAt),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        val expected = createdAt + TimeUnit.DAYS.toMillis(CareSchedule.FIRST_FERTILIZE_GRACE_DAYS.toLong())
        assertEquals(expected, status.nextFertilizingDueAt)
        assertFalse(status.isFertilizingDueSoon)
        assertFalse(status.isFertilizingOverdue)
    }

    @Test
    fun `never fertilized with createdAt exactly 30 days ago is due today`() {
        val createdAt = now - TimeUnit.DAYS.toMillis(CareSchedule.FIRST_FERTILIZE_GRACE_DAYS.toLong())
        val status = CareSchedule.computeStatus(
            plant = plantWith(fertilizingIntervalDays = 14, createdAt = createdAt),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertTrue(status.isFertilizingDueSoon)
        assertFalse(status.isFertilizingOverdue)
    }

    @Test
    fun `never fertilized with createdAt more than 30 days ago is overdue`() {
        val createdAt = now - TimeUnit.DAYS.toMillis(45)
        val status = CareSchedule.computeStatus(
            plant = plantWith(fertilizingIntervalDays = 14, createdAt = createdAt),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertTrue(status.isFertilizingOverdue)
    }

    @Test
    fun `never fertilized with no fertilizing interval stays null`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(fertilizingIntervalDays = null, createdAt = now - TimeUnit.DAYS.toMillis(45)),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertNull(status.nextFertilizingDueAt)
        assertFalse(status.isFertilizingOverdue)
        assertFalse(status.isFertilizingDueSoon)
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

    // ---- Repotting reminders (#232) ----

    @Test
    fun `never repotted with interval anchors first due to createdAt plus interval`() {
        val createdAt = now - TimeUnit.DAYS.toMillis(30)
        val status = CareSchedule.computeStatus(
            plant = plantWith(repottingIntervalDays = 365, createdAt = createdAt),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now,
            lastRepottedAt = null
        )
        assertEquals(createdAt + TimeUnit.DAYS.toMillis(365), status.nextRepottingDueAt)
        assertFalse(status.isRepottingOverdue)
        assertFalse(status.isRepottingDueSoon)
    }

    @Test
    fun `repotting overdue when last repotting older than interval`() {
        val lastRepotted = now - TimeUnit.DAYS.toMillis(400)
        val status = CareSchedule.computeStatus(
            plant = plantWith(repottingIntervalDays = 365),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now,
            lastRepottedAt = lastRepotted
        )
        assertEquals(lastRepotted + TimeUnit.DAYS.toMillis(365), status.nextRepottingDueAt)
        assertTrue(status.isRepottingOverdue)
    }

    @Test
    fun `no repotting interval leaves repotting status unset`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(repottingIntervalDays = null, createdAt = now - TimeUnit.DAYS.toMillis(5000)),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertNull(status.nextRepottingDueAt)
        assertFalse(status.isRepottingDueSoon)
        assertFalse(status.isRepottingOverdue)
    }

    // ---- Custom reminders (#232) ----

    @Test
    fun `no custom reminders yields an empty status list`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertTrue(status.customReminderStatuses.isEmpty())
    }

    @Test
    fun `never-done custom reminder anchors first due to its own createdAt plus interval`() {
        val plantCreatedAt = now - TimeUnit.DAYS.toMillis(3)
        val reminderCreatedAt = now - TimeUnit.DAYS.toMillis(1)
        val reminder = CustomReminder(
            id = 1L,
            plantId = 1L,
            name = "Neem oil",
            intervalDays = 7,
            createdAt = reminderCreatedAt
        )
        val status = CareSchedule.computeStatus(
            plant = plantWith(createdAt = plantCreatedAt),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now,
            customReminders = listOf(reminder)
        )
        val reminderStatus = status.customReminderStatuses.single()
        assertEquals(reminder, reminderStatus.reminder)
        assertEquals(reminderCreatedAt + TimeUnit.DAYS.toMillis(7), reminderStatus.nextDueAt)
        assertFalse(reminderStatus.isOverdue)
        assertFalse(reminderStatus.isDueSoon)
    }

    @Test
    fun `never-done custom reminder added long after the plant is not overdue on creation`() {
        val plantCreatedAt = now - TimeUnit.DAYS.toMillis(200)
        val reminderCreatedAt = now
        val reminder = CustomReminder(
            id = 1L,
            plantId = 1L,
            name = "Fungicide spray",
            intervalDays = 7,
            createdAt = reminderCreatedAt
        )
        val status = CareSchedule.computeStatus(
            plant = plantWith(createdAt = plantCreatedAt),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now,
            customReminders = listOf(reminder)
        )
        val reminderStatus = status.customReminderStatuses.single()
        assertEquals(reminderCreatedAt + TimeUnit.DAYS.toMillis(7), reminderStatus.nextDueAt)
        assertFalse(reminderStatus.isOverdue)
    }

    @Test
    fun `custom reminder is overdue when lastDoneAt plus interval is in the past`() {
        val lastDoneAt = now - TimeUnit.DAYS.toMillis(10)
        val reminder = CustomReminder(
            id = 1L,
            plantId = 1L,
            name = "Neem oil",
            intervalDays = 7,
            lastDoneAt = lastDoneAt
        )
        val status = CareSchedule.computeStatus(
            plant = plantWith(),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now,
            customReminders = listOf(reminder)
        )
        val reminderStatus = status.customReminderStatuses.single()
        assertEquals(lastDoneAt + TimeUnit.DAYS.toMillis(7), reminderStatus.nextDueAt)
        assertTrue(reminderStatus.isOverdue)
        assertFalse(reminderStatus.isDueSoon)
    }

    @Test
    fun `custom reminder is due today when lastDoneAt plus interval is today`() {
        val lastDoneAt = now - TimeUnit.DAYS.toMillis(7)
        val reminder = CustomReminder(
            id = 1L,
            plantId = 1L,
            name = "Neem oil",
            intervalDays = 7,
            lastDoneAt = lastDoneAt
        )
        val status = CareSchedule.computeStatus(
            plant = plantWith(),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now,
            customReminders = listOf(reminder)
        )
        val reminderStatus = status.customReminderStatuses.single()
        assertFalse(reminderStatus.isOverdue)
        assertTrue(reminderStatus.isDueSoon)
    }

    @Test
    fun `multiple custom reminders each get their own independent status`() {
        val dueReminder = CustomReminder(
            id = 1L,
            plantId = 1L,
            name = "Overdue treatment",
            intervalDays = 7,
            lastDoneAt = now - TimeUnit.DAYS.toMillis(10)
        )
        val notDueReminder = CustomReminder(
            id = 2L,
            plantId = 1L,
            name = "Fresh reminder",
            intervalDays = 30,
            lastDoneAt = now - TimeUnit.DAYS.toMillis(1)
        )
        val status = CareSchedule.computeStatus(
            plant = plantWith(),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now,
            customReminders = listOf(dueReminder, notDueReminder)
        )
        assertEquals(2, status.customReminderStatuses.size)
        assertTrue(status.customReminderStatuses.single { it.reminder.id == 1L }.isOverdue)
        val fresh = status.customReminderStatuses.single { it.reminder.id == 2L }
        assertFalse(fresh.isOverdue)
        assertFalse(fresh.isDueSoon)
    }
    // --- isWateringOnSchedule (#586, product ADR-0030) ---
    // The single question every watering surface asks to decide whether to prompt for a reason.

    private fun onScheduleFor(daysSinceWatering: Long, intervalDays: Int = 7): Boolean =
        CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = intervalDays),
            lastWateredAt = now - TimeUnit.DAYS.toMillis(daysSinceWatering),
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now
        ).isWateringOnSchedule

    @Test
    fun `watering exactly on the interval is on schedule`() {
        assertTrue(onScheduleFor(daysSinceWatering = 7))
    }

    @Test
    fun `watering within the gap-agreement tolerance is on schedule`() {
        // 0.15 * 7 = 1.05, so 6 and 8 days both land inside the band on a 7-day plant.
        assertTrue(onScheduleFor(daysSinceWatering = 6))
        assertTrue(onScheduleFor(daysSinceWatering = 8))
    }

    @Test
    fun `watering well outside the tolerance is off schedule in both directions`() {
        assertFalse(onScheduleFor(daysSinceWatering = 3))
        assertFalse(onScheduleFor(daysSinceWatering = 12))
    }

    /** Scale-invariance is the reason GAP_AGREEMENT_TOLERANCE was reused instead of a day count. */
    @Test
    fun `the tolerance scales with the interval`() {
        // 4 days early is off schedule on a 7-day plant but well inside the band on a 30-day one.
        assertFalse(onScheduleFor(daysSinceWatering = 3, intervalDays = 7))
        assertTrue(onScheduleFor(daysSinceWatering = 26, intervalDays = 30))
    }

    @Test
    fun `a never-watered plant is on schedule, since there is no gap to be off by`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        assertTrue(status.isWateringOnSchedule)
    }

    @Test
    fun `a plant with no watering interval is on schedule, since there is no schedule to be off`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(),
            lastWateredAt = now - TimeUnit.DAYS.toMillis(90),
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now
        )
        assertTrue(status.isWateringOnSchedule)
    }

    /**
     * #586: which side of the schedule an off-schedule watering fell on, so the reason prompt can
     * ask "why was it late?" rather than "why now?". A 7-day plant last watered 12 days ago.
     */
    @Test
    fun `a gap that ran long is reported as long`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = now - TimeUnit.DAYS.toMillis(12),
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now
        )
        assertFalse(status.isWateringOnSchedule)
        assertTrue(status.isWateringGapLong)
    }

    /** The early half of the same test: watered at day 2 of a 7-day interval. */
    @Test
    fun `a gap that is still short is not reported as long`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = now - TimeUnit.DAYS.toMillis(2),
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now
        )
        assertFalse(status.isWateringOnSchedule)
        assertFalse(status.isWateringGapLong)
    }

    /**
     * The reason `isWateringGapLong` is derived from the gap rather than from [PlantCareStatus
     * .isOverdue]: a deferral pushes the due date out, so this plant is **not** overdue, yet its gap
     * since the last watering has still run long and the prompt must say so.
     */
    @Test
    fun `a deferred plant with a long gap is not overdue but its gap still ran long`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(
                wateringIntervalDays = 7,
                wateringDueDateOverride = now + TimeUnit.DAYS.toMillis(5)
            ),
            lastWateredAt = now - TimeUnit.DAYS.toMillis(12),
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now
        )
        assertFalse(status.isOverdue)
        assertTrue(status.isWateringGapLong)
    }

    /**
     * A deferral moves the due *date*, not the interval — so watering on the pushed-out date is still
     * an off-schedule gap, and the user is still asked why. That is deliberate: "I put it off and then
     * watered three days later" is exactly the case where the answer decides whether the plant can go
     * longer or the user was simply busy.
     *
     * A 7-day plant last watered 10 days ago, deferred by 3 days so the new due date is exactly
     * [now] — the moment the watering happens. The 10-day gap is what the test turns on;
     * [com.yapt.planttracker.domain.model.Plant.wateringDueDateOverride] deliberately does not enter
     * [CareSchedule.wateringOnScheduleNow] at all, which is precisely why a deferral cannot launder
     * an off-schedule gap into an on-schedule one.
     */
    @Test
    fun `a rescheduled plant watered on its new due date is still off schedule`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(
                wateringIntervalDays = 7,
                wateringDueDateOverride = now
            ),
            lastWateredAt = now - TimeUnit.DAYS.toMillis(10),
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now
        )
        assertFalse(status.isWateringOnSchedule)
    }

    // --- isWateringOnScheduleAt / isWateringGapLongAt (#654) ---
    // Public wrappers for a backdated quick-water's chosen-date gate: same comparison as
    // isWateringOnSchedule/isWateringGapLong above, generalized to a caller-supplied date instead of
    // always "now".

    @Test
    fun `isWateringOnScheduleAt with today reproduces isWateringOnSchedule's own result`() {
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(12)
        val effectiveIntervalDays = 7

        assertEquals(
            onScheduleFor(daysSinceWatering = 12),
            CareSchedule.isWateringOnScheduleAt(lastWateredAt, effectiveIntervalDays, chosenDate = now)
        )
    }

    @Test
    fun `isWateringOnScheduleAt treats an earlier chosen date as on schedule when the gap agrees there`() {
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(20)
        // Backdating the log to 7 days after the last watering (a 7-day plant) is on schedule for that
        // day, even though "today" (20 days later) would be well off schedule.
        val chosenDate = lastWateredAt + TimeUnit.DAYS.toMillis(7)

        assertTrue(CareSchedule.isWateringOnScheduleAt(lastWateredAt, 7, chosenDate))
    }

    @Test
    fun `isWateringOnScheduleAt is true with no interval or no prior watering, same as the now variant`() {
        assertTrue(CareSchedule.isWateringOnScheduleAt(lastWateredAt = null, effectiveIntervalDays = 7, now))
        assertTrue(CareSchedule.isWateringOnScheduleAt(lastWateredAt = now, effectiveIntervalDays = null, now))
    }

    @Test
    fun `isWateringGapLongAt reports the direction for a chosen date, not now`() {
        val lastWateredAt = now - TimeUnit.DAYS.toMillis(20)
        val onScheduleChosenDate = lastWateredAt + TimeUnit.DAYS.toMillis(7)
        val lateChosenDate = lastWateredAt + TimeUnit.DAYS.toMillis(15)

        assertFalse(CareSchedule.isWateringGapLongAt(lastWateredAt, 7, onScheduleChosenDate))
        assertTrue(CareSchedule.isWateringGapLongAt(lastWateredAt, 7, lateChosenDate))
    }
}
