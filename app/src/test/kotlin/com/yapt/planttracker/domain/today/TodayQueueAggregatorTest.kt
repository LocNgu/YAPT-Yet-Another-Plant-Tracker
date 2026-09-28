package com.yapt.planttracker.domain.today

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import com.yapt.planttracker.domain.schedule.Hemisphere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TodayQueueAggregatorTest {

    private val today = LocalDate.of(2026, 9, 27)
    private val zone = ZoneId.systemDefault()

    @Test
    fun `aggregates every scheduled source inside the three day horizon`() {
        val plant = plant(
            wateringIntervalDays = 7,
            fertilizingIntervalDays = 10,
            repottingIntervalDays = 30
        )
        val reminder = CustomReminder(
            id = 41L,
            plantId = plant.id,
            name = "Neem",
            intervalDays = 3,
            createdAt = millis(today)
        )
        val input = input(
            plants = listOf(plant),
            logs = listOf(
                log(plant.id, CareType.WATER, today.minusDays(7)),
                log(plant.id, CareType.FERTILIZE, today.minusDays(9)),
                log(plant.id, CareType.REPOT, today.minusDays(28)),
                CareLog(
                    plantId = plant.id,
                    careType = CareType.PHOTO,
                    loggedAt = millis(today.minusDays(30)),
                    photoUri = "care.jpg"
                )
            ),
            reminders = listOf(reminder),
            photoEnabled = true
        )

        val result = TodayQueueAggregator.build(input)

        assertEquals(
            setOf(
                TodayCareKind.WATER,
                TodayCareKind.FERTILIZE,
                TodayCareKind.REPOT,
                TodayCareKind.CUSTOM_REMINDER,
                TodayCareKind.PHOTO
            ),
            result.tasks.map { it.kind }.toSet()
        )
    }

    @Test
    fun `active linked issue relabels one reminder while resolved and unlinked issues add nothing`() {
        val plant = plant()
        val activeReminder = reminder(1L, plant.id, "Spray")
        val resolvedReminder = reminder(2L, plant.id, "Inspect")
        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(plant),
                reminders = listOf(activeReminder, resolvedReminder),
                issues = listOf(
                    PlantIssue(id = 10L, plantId = plant.id, name = "Mites", linkedReminderId = 1L),
                    PlantIssue(
                        id = 11L,
                        plantId = plant.id,
                        name = "Old issue",
                        linkedReminderId = 2L,
                        resolvedAt = millis(today.minusDays(1))
                    ),
                    PlantIssue(id = 12L, plantId = plant.id, name = "Unlinked")
                )
            )
        )

        assertEquals(2, result.tasks.size)
        assertEquals(TodayCareKind.ISSUE_TREATMENT, result.tasks.first { it.id == "custom:1" }.kind)
        assertEquals("Mites", result.tasks.first { it.id == "custom:1" }.issueName)
        assertEquals(TodayCareKind.CUSTOM_REMINDER, result.tasks.first { it.id == "custom:2" }.kind)
    }

    @Test
    fun `liquid fertilizer due with watering is one combined task`() {
        val plant = plant(
            useLiquidFertilizer = true,
            wateringIntervalDays = 7,
            fertilizingIntervalDays = 14
        )
        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(plant),
                logs = listOf(
                    log(plant.id, CareType.WATER, today.minusDays(7)),
                    log(plant.id, CareType.FERTILIZE, today.minusDays(20))
                )
            )
        )

        assertEquals(listOf(TodayCareKind.WATER_AND_FERTILIZE), result.tasks.map { it.kind })
    }

    @Test
    fun `liquid fertilizer does not create a standalone fertilizing task`() {
        val plant = plant(
            useLiquidFertilizer = true,
            wateringIntervalDays = 14,
            fertilizingIntervalDays = 7
        )
        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(plant),
                logs = listOf(
                    log(plant.id, CareType.WATER, today.minusDays(5)),
                    log(plant.id, CareType.FERTILIZE, today.minusDays(10))
                )
            )
        )

        assertTrue(result.tasks.isEmpty())
    }

    @Test
    fun `photo task uses newest timestamp and ignores popup session suppression`() {
        val plant = plant(createdAt = millis(today.minusDays(90)))
        PhotoReminderPolicy.shownThisSession += plant.id
        try {
            val result = TodayQueueAggregator.build(
                input(
                    plants = listOf(plant),
                    logs = listOf(
                        CareLog(
                            plantId = plant.id,
                            careType = CareType.PHOTO,
                            loggedAt = millis(today.minusDays(40)),
                            photoUri = "old.jpg"
                        )
                    ),
                    photos = listOf(
                        PlantPhoto(
                            plantId = plant.id,
                            uri = "new.jpg",
                            capturedAt = millis(today.minusDays(10))
                        )
                    ),
                    photoEnabled = true
                )
            )

            assertFalse(result.tasks.any { it.kind == TodayCareKind.PHOTO })

            val dueResult = TodayQueueAggregator.build(
                input(plants = listOf(plant), photoEnabled = true)
            )
            assertTrue(dueResult.tasks.any { it.kind == TodayCareKind.PHOTO })
        } finally {
            PhotoReminderPolicy.shownThisSession -= plant.id
        }
    }

    @Test
    fun `photo reminders disabled produce no photo task`() {
        val plant = plant(createdAt = millis(today.minusDays(90)))

        val enabled = TodayQueueAggregator.build(input(plants = listOf(plant), photoEnabled = true))
        val disabled = TodayQueueAggregator.build(input(plants = listOf(plant), photoEnabled = false))

        assertTrue(enabled.tasks.any { it.kind == TodayCareKind.PHOTO })
        assertFalse(disabled.tasks.any { it.kind == TodayCareKind.PHOTO })
    }

    @Test
    fun `active watering reschedule moves the task to the override day`() {
        val rescheduled = plant(wateringIntervalDays = 7, wateringDueDateOverride = millis(today.plusDays(2)))
        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(rescheduled),
                logs = listOf(log(rescheduled.id, CareType.WATER, today.minusDays(7)))
            )
        )

        val task = result.tasks.single()
        assertEquals(TodayCareKind.WATER, task.kind)
        assertEquals(millis(today.plusDays(2)), task.dueAt)
        assertEquals(TodayTaskBucket.Upcoming(today.plusDays(2).toEpochDay()), task.bucket)
    }

    @Test
    fun `watering reschedule beyond the horizon removes the task`() {
        val rescheduled = plant(wateringIntervalDays = 7, wateringDueDateOverride = millis(today.plusDays(5)))
        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(rescheduled),
                logs = listOf(log(rescheduled.id, CareType.WATER, today.minusDays(7)))
            )
        )

        assertTrue(result.tasks.isEmpty())
    }

    @Test
    fun `archived and fully dormant care are excluded`() {
        val archived = plant(id = 1L, wateringIntervalDays = 1, archivedAt = millis(today))
        val dormant = plant(
            id = 2L,
            wateringIntervalDays = 1,
            fertilizingIntervalDays = 1,
            dormancyStartMonth = 9,
            dormancyEndMonth = 9
        )
        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(archived, dormant),
                logs = listOf(
                    log(archived.id, CareType.WATER, today.minusDays(5)),
                    log(dormant.id, CareType.WATER, today.minusDays(5)),
                    log(dormant.id, CareType.FERTILIZE, today.minusDays(5))
                )
            )
        )

        assertEquals(1, result.activePlantCount)
        assertTrue(result.tasks.isEmpty())
    }

    @Test
    fun `dormant cadence watering participates normally`() {
        val plant = plant(
            wateringIntervalDays = 30,
            dormancyStartMonth = 9,
            dormancyEndMonth = 9,
            dormantWateringIntervalDays = 7
        )
        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(plant),
                logs = listOf(log(plant.id, CareType.WATER, today.minusDays(7)))
            )
        )

        assertEquals(listOf(TodayCareKind.WATER), result.tasks.map { it.kind })
    }

    @Test
    fun `overdue watering whose due date was dormant returns after dormancy ends`() {
        val september = LocalDate.of(2026, 9, 1)
        val plant = plant(
            wateringIntervalDays = 7,
            dormancyStartMonth = 8,
            dormancyEndMonth = 8
        )

        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(plant),
                logs = listOf(log(plant.id, CareType.WATER, LocalDate.of(2026, 8, 10))),
                inputToday = september
            )
        )

        assertEquals(TodayTaskBucket.Overdue, result.tasks.single().bucket)
        assertEquals(TodayCareKind.WATER, result.tasks.single().kind)
    }

    @Test
    fun `overdue fertilizing whose due date was dormant returns after dormancy ends`() {
        val september = LocalDate.of(2026, 9, 1)
        val plant = plant(
            fertilizingIntervalDays = 7,
            dormancyStartMonth = 8,
            dormancyEndMonth = 8
        )

        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(plant),
                logs = listOf(log(plant.id, CareType.FERTILIZE, LocalDate.of(2026, 8, 10))),
                inputToday = september
            )
        )

        assertEquals(TodayTaskBucket.Overdue, result.tasks.single().bucket)
        assertEquals(TodayCareKind.FERTILIZE, result.tasks.single().kind)
    }

    @Test
    fun `current dormancy still suppresses watering and fertilizing`() {
        val plant = plant(
            wateringIntervalDays = 1,
            fertilizingIntervalDays = 1,
            dormancyStartMonth = 9,
            dormancyEndMonth = 9
        )

        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(plant),
                logs = listOf(
                    log(plant.id, CareType.WATER, today.minusDays(5)),
                    log(plant.id, CareType.FERTILIZE, today.minusDays(5))
                )
            )
        )

        assertTrue(result.tasks.isEmpty())
    }

    @Test
    fun `upcoming watering and fertilizing inside dormancy stay suppressed`() {
        val september = LocalDate.of(2026, 9, 29)
        val plant = plant(
            wateringIntervalDays = 2,
            fertilizingIntervalDays = 2,
            dormancyStartMonth = 10,
            dormancyEndMonth = 10
        )

        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(plant),
                logs = listOf(
                    log(plant.id, CareType.WATER, september),
                    log(plant.id, CareType.FERTILIZE, september)
                ),
                inputToday = september
            )
        )

        assertTrue(result.tasks.isEmpty())
    }

    @Test
    fun `includes day three excludes day four and orders deterministically`() {
        val beta = plant(id = 1L, name = "Beta", wateringIntervalDays = 3)
        val alpha = plant(id = 2L, name = "Alpha", wateringIntervalDays = 3)
        val later = plant(id = 3L, name = "Later", wateringIntervalDays = 4)
        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(beta, later, alpha),
                logs = listOf(
                    log(beta.id, CareType.WATER, today),
                    log(alpha.id, CareType.WATER, today),
                    log(later.id, CareType.WATER, today)
                )
            )
        )

        assertEquals(listOf("Alpha", "Beta"), result.tasks.map { it.plant.name })
        assertTrue(result.tasks.all { it.bucket == TodayTaskBucket.Upcoming(today.plusDays(3).toEpochDay()) })
    }

    @Test
    fun `care type sections order watering first and hide empty sections`() {
        val fern = plant()
        val tasks = listOf(
            task("photo:1", fern, TodayCareKind.PHOTO, TodayTaskBucket.Today),
            task("repot:1", fern, TodayCareKind.REPOT, TodayTaskBucket.Today),
            task("custom:1", fern, TodayCareKind.CUSTOM_REMINDER, TodayTaskBucket.Today),
            task("fertilize:1", fern, TodayCareKind.FERTILIZE, TodayTaskBucket.Today),
            task("issue:1", fern, TodayCareKind.ISSUE_TREATMENT, TodayTaskBucket.Today),
            task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Overdue)
        )

        val sections = careTypeSections(tasks)

        assertEquals(
            listOf(
                TodayCareSection.WATERING,
                TodayCareSection.ISSUE_TREATMENTS,
                TodayCareSection.FERTILIZING,
                TodayCareSection.CUSTOM_REMINDERS,
                TodayCareSection.REPOTTING,
                TodayCareSection.PHOTOS
            ),
            sections.map { it.section }
        )
        assertEquals(
            listOf(TodayCareSection.WATERING, TodayCareSection.PHOTOS),
            careTypeSections(tasks.filter { it.kind == TodayCareKind.WATER || it.kind == TodayCareKind.PHOTO })
                .map { it.section }
        )
        assertTrue(careTypeSections(emptyList()).isEmpty())
    }

    @Test
    fun `care type sections keep every task once and preserve queue order within a section`() {
        val fern = plant(id = 1L, name = "Fern")
        val aloe = plant(id = 2L, name = "Aloe")
        val tasks = listOf(
            task("water:2", aloe, TodayCareKind.WATER, TodayTaskBucket.Overdue),
            task("fertilize:1", fern, TodayCareKind.FERTILIZE, TodayTaskBucket.Today),
            task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Today),
            task("custom:1", fern, TodayCareKind.CUSTOM_REMINDER, TodayTaskBucket.Today)
        )

        val sections = careTypeSections(tasks)

        assertEquals(tasks.map { it.id }.sorted(), sections.flatMap { it.tasks }.map { it.id }.sorted())
        assertEquals(
            listOf("water:2", "water:1"),
            sections.first { it.section == TodayCareSection.WATERING }.tasks.map { it.id }
        )
    }

    @Test
    fun `combined water and fertilize task sits under watering only`() {
        val fern = plant()
        val combined = task("water_fertilize:1", fern, TodayCareKind.WATER_AND_FERTILIZE, TodayTaskBucket.Today)

        val sections = careTypeSections(listOf(combined))

        assertEquals(listOf(TodayCareSection.WATERING), sections.map { it.section })
        assertEquals(listOf(combined), sections.single().tasks)
    }

    @Suppress("LongParameterList")
    private fun plant(
        id: Long = 1L,
        name: String = "Plant",
        wateringIntervalDays: Int? = null,
        fertilizingIntervalDays: Int? = null,
        repottingIntervalDays: Int? = null,
        useLiquidFertilizer: Boolean = false,
        archivedAt: Long? = null,
        createdAt: Long = millis(today.minusDays(60)),
        dormancyStartMonth: Int? = null,
        dormancyEndMonth: Int? = null,
        dormantWateringIntervalDays: Int? = null,
        wateringDueDateOverride: Long? = null
    ) = Plant(
        id = id,
        name = name,
        wateringIntervalDays = wateringIntervalDays,
        fertilizingIntervalDays = fertilizingIntervalDays,
        repottingIntervalDays = repottingIntervalDays,
        useLiquidFertilizer = useLiquidFertilizer,
        archivedAt = archivedAt,
        createdAt = createdAt,
        updatedAt = createdAt,
        dormancyStartMonth = dormancyStartMonth,
        dormancyEndMonth = dormancyEndMonth,
        dormantWateringIntervalDays = dormantWateringIntervalDays,
        wateringDueDateOverride = wateringDueDateOverride,
        pinIntervalToBase = true
    )

    private fun reminder(id: Long, plantId: Long, name: String) = CustomReminder(
        id = id,
        plantId = plantId,
        name = name,
        intervalDays = 1,
        createdAt = millis(today.minusDays(2))
    )

    private fun log(plantId: Long, type: CareType, date: LocalDate) =
        CareLog(plantId = plantId, careType = type, loggedAt = millis(date))

    private fun task(id: String, plant: Plant, kind: TodayCareKind, bucket: TodayTaskBucket) = TodayCareTask(
        id = id,
        plant = plant,
        kind = kind,
        dueAt = millis(today),
        bucket = bucket
    )

    @Suppress("LongParameterList")
    private fun input(
        plants: List<Plant>,
        logs: List<CareLog> = emptyList(),
        reminders: List<CustomReminder> = emptyList(),
        issues: List<PlantIssue> = emptyList(),
        photos: List<PlantPhoto> = emptyList(),
        photoEnabled: Boolean = false,
        inputToday: LocalDate = today
    ) = TodayQueueInput(
        plants = plants,
        careLogs = logs,
        customReminders = reminders,
        issues = issues,
        photos = photos,
        today = inputToday,
        seasonalAmplitude = 0.0,
        photoReminderEnabled = photoEnabled,
        hemisphere = Hemisphere.NORTHERN
    )

    private fun millis(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
}
