package com.yapt.planttracker.domain.notification

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.CareSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ReminderNotificationComposerTest {

    private val now = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun plantWith(
        id: Long = 1L,
        wateringIntervalDays: Int? = null,
        fertilizingIntervalDays: Int? = null,
        useLiquidFertilizer: Boolean = false,
        createdAt: Long = now
    ) = Plant(
        id = id,
        name = "Plant $id",
        wateringIntervalDays = wateringIntervalDays,
        fertilizingIntervalDays = fertilizingIntervalDays,
        useLiquidFertilizer = useLiquidFertilizer,
        createdAt = createdAt
    )

    @Test
    fun `computeCareReminderItems returns empty list when nothing is due`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        assertTrue(ReminderNotificationComposer.computeCareReminderItems(status, now).isEmpty())
    }

    @Test
    fun `computeCareReminderItems returns WateringOverdue when watering is overdue`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(10)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        val items = ReminderNotificationComposer.computeCareReminderItems(status, now)
        assertEquals(1, items.size)
        assertTrue(items[0] is CareReminderItem.WateringOverdue)
    }

    @Test
    fun `computeCareReminderItems returns WateringDueToday when due soon`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 7),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        val items = ReminderNotificationComposer.computeCareReminderItems(status, now)
        assertEquals(listOf(CareReminderItem.WateringDueToday), items)
    }

    @Test
    fun `computeCareReminderItems adds FertilizeWithWatering for liquid fertilizer when watering also due`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(
                wateringIntervalDays = 7,
                fertilizingIntervalDays = 14,
                useLiquidFertilizer = true
            ),
            lastWateredAt = null,
            lastFertilizedAt = now - TimeUnit.DAYS.toMillis(20),
            totalLogs = 0,
            now = now
        )

        val items = ReminderNotificationComposer.computeCareReminderItems(status, now)
        assertEquals(
            listOf(CareReminderItem.WateringDueToday, CareReminderItem.FertilizeWithWatering),
            items
        )
    }

    @Test
    fun `computeCareReminderItems omits fertilizing for liquid fertilizer when watering is not due`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(
                fertilizingIntervalDays = 14,
                useLiquidFertilizer = true
            ),
            lastWateredAt = null,
            lastFertilizedAt = now - TimeUnit.DAYS.toMillis(20),
            totalLogs = 0,
            now = now
        )

        assertTrue(ReminderNotificationComposer.computeCareReminderItems(status, now).isEmpty())
    }

    @Test
    fun `computeDueReminders excludes plants with nothing due`() {
        val notDue = CareSchedule.computeStatus(
            plant = plantWith(id = 1L),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        val due = CareSchedule.computeStatus(
            plant = plantWith(id = 2L, wateringIntervalDays = 7),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        val reminders = ReminderNotificationComposer.computeDueReminders(listOf(notDue, due), now)
        assertEquals(1, reminders.size)
        assertEquals(2L, reminders[0].status.plant.id)
    }

    @Test
    fun `computeDueReminders returns empty list when zero plants are due`() {
        val notDue1 = CareSchedule.computeStatus(
            plant = plantWith(id = 1L),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )
        val notDue2 = CareSchedule.computeStatus(
            plant = plantWith(id = 2L, fertilizingIntervalDays = 14, useLiquidFertilizer = true),
            lastWateredAt = null,
            lastFertilizedAt = now - TimeUnit.DAYS.toMillis(20),
            totalLogs = 0,
            now = now
        )

        assertTrue(ReminderNotificationComposer.computeDueReminders(listOf(notDue1, notDue2), now).isEmpty())
    }

    @Test
    fun `computeDueReminders drops a fertilizing-only plant when fertilizing notifications are off`() {
        // Non-liquid plant, never fertilized, created 60 days ago -> past the 30-day grace ->
        // fertilizing overdue. No watering interval -> watering not due. So only fertilizing is due.
        val fertilizingOnly = CareSchedule.computeStatus(
            plant = plantWith(
                id = 1L,
                fertilizingIntervalDays = 14,
                createdAt = now - TimeUnit.DAYS.toMillis(60)
            ),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now
        )

        assertTrue(
            ReminderNotificationComposer.computeDueReminders(
                listOf(fertilizingOnly),
                now,
                fertilizingNotificationsEnabled = false
            ).isEmpty()
        )
        // With the toggle on (default) it is still reminded.
        assertEquals(1, ReminderNotificationComposer.computeDueReminders(listOf(fertilizingOnly), now).size)
    }

    @Test
    fun `computeDueReminders keeps a both-due plant when fertilizing notifications are off`() {
        // Never-watered plant with a watering interval -> due today; also fertilizing overdue.
        val bothDue = CareSchedule.computeStatus(
            plant = plantWith(id = 1L, wateringIntervalDays = 7, fertilizingIntervalDays = 14),
            lastWateredAt = null,
            lastFertilizedAt = now - TimeUnit.DAYS.toMillis(60),
            totalLogs = 0,
            now = now
        )

        val reminders = ReminderNotificationComposer.computeDueReminders(
            listOf(bothDue),
            now,
            fertilizingNotificationsEnabled = false
        )

        assertEquals(1, reminders.size)
        // Full body is retained, including the fertilizing line.
        assertEquals(2, reminders[0].items.size)
        assertEquals(CareReminderItem.WateringDueToday, reminders[0].items[0])
        assertTrue(reminders[0].items[1] is CareReminderItem.FertilizingOverdue)
    }

    @Test
    fun `computeDueReminders count matches the combined notification's plant count`() {
        val statuses = (1..3L).map { id ->
            CareSchedule.computeStatus(
                plant = plantWith(id = id, wateringIntervalDays = 7),
                lastWateredAt = null,
                lastFertilizedAt = null,
                totalLogs = 0,
                now = now
            )
        }

        assertEquals(3, ReminderNotificationComposer.computeDueReminders(statuses, now).size)
    }
}
