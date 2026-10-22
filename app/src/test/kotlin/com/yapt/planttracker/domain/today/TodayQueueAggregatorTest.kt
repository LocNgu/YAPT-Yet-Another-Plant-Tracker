package com.yapt.planttracker.domain.today

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import com.yapt.planttracker.domain.schedule.FertilizingSeason
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
    fun `aggregates every scheduled source inside the default horizon`() {
        val plant = plant(
            wateringIntervalDays = 7,
            fertilizingIntervalDays = 10,
            repottingIntervalDays = 30
        )
        val reminder = CustomReminder(
            id = 41L,
            plantId = plant.id,
            name = "Neem",
            intervalDays = 1,
            createdAt = millis(today)
        )
        val input = input(
            plants = listOf(plant),
            logs = listOf(
                log(plant.id, CareType.WATER, today.minusDays(7)),
                log(plant.id, CareType.FERTILIZE, today.minusDays(9)),
                log(plant.id, CareType.REPOT, today.minusDays(30)),
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
        val rescheduled = plant(wateringIntervalDays = 7, wateringDueDateOverride = millis(today.plusDays(1)))
        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(rescheduled),
                logs = listOf(log(rescheduled.id, CareType.WATER, today.minusDays(7)))
            )
        )

        val task = result.tasks.single()
        assertEquals(TodayCareKind.WATER, task.kind)
        assertEquals(millis(today.plusDays(1)), task.dueAt)
        assertEquals(TodayTaskBucket.Upcoming(today.plusDays(1).toEpochDay()), task.bucket)
    }

    @Test
    fun `watering reschedule beyond the horizon removes the task`() {
        val rescheduled = plant(wateringIntervalDays = 7, wateringDueDateOverride = millis(today.plusDays(2)))
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
    fun `hidden plants produce no tasks and are not counted as active`() {
        val hidden = plant(id = 1L, wateringIntervalDays = 1).copy(hiddenAt = millis(today))
        val shown = plant(id = 2L, wateringIntervalDays = 1)
        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(hidden, shown),
                logs = listOf(
                    log(hidden.id, CareType.WATER, today.minusDays(5)),
                    log(shown.id, CareType.WATER, today.minusDays(5))
                )
            )
        )

        assertEquals(1, result.activePlantCount)
        assertTrue(result.tasks.all { it.plant.id == shown.id })
        assertTrue(result.tasks.isNotEmpty())
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
    fun `includes tomorrow excludes the day after and orders deterministically`() {
        val beta = plant(id = 1L, name = "Beta", wateringIntervalDays = 1)
        val alpha = plant(id = 2L, name = "Alpha", wateringIntervalDays = 1)
        val later = plant(id = 3L, name = "Later", wateringIntervalDays = 2)
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
        assertTrue(result.tasks.all { it.bucket == TodayTaskBucket.Upcoming(today.plusDays(1).toEpochDay()) })
    }

    // ---- Planned repot (#809, product ADR-0057) ----

    private val springStart = LocalDate.of(2027, 3, 1)

    private fun springPlanPlant(id: Long = 1L, name: String = "Plant", repottingIntervalDays: Int? = null) =
        plant(id = id, name = name, repottingIntervalDays = repottingIntervalDays)
            .copy(repotPlanSeasonStartAt = millis(springStart), repotPlanMadeAt = millis(today))

    private fun repotTaskOn(day: LocalDate, plant: Plant = springPlanPlant()): TodayCareTask? =
        TodayQueueAggregator.build(input(plants = listOf(plant), inputToday = day))
            .tasks.singleOrNull { it.kind == TodayCareKind.REPOT }

    @Test
    fun `an in-season plan is due today for its whole season, never overdue`() {
        for (day in listOf(springStart, LocalDate.of(2027, 4, 15), LocalDate.of(2027, 5, 31))) {
            val task = repotTaskOn(day)!!

            assertEquals(TodayTaskBucket.Today, task.bucket)
            assertEquals(millis(day), task.dueAt)
            assertEquals(FertilizingSeason.SPRING, task.repotPlanSeason)
        }
    }

    @Test
    fun `a plan whose season has ended is overdue since the season's last day`() {
        for (day in listOf(LocalDate.of(2027, 6, 1), LocalDate.of(2027, 11, 20))) {
            val task = repotTaskOn(day)!!

            assertEquals(TodayTaskBucket.Overdue, task.bucket)
            assertEquals(millis(LocalDate.of(2027, 5, 31)), task.dueAt)
            assertEquals(FertilizingSeason.SPRING, task.repotPlanSeason)
        }
    }

    @Test
    fun `an upcoming plan enters the horizon from its season start`() {
        val task = repotTaskOn(springStart.minusDays(1))!!

        assertEquals(TodayTaskBucket.Upcoming(springStart.toEpochDay()), task.bucket)
        assertEquals(millis(springStart), task.dueAt)
        assertEquals(null, repotTaskOn(springStart.minusDays(2)))
    }

    @Test
    fun `an upcoming plan suppresses an overdue interval repot`() {
        val plant = springPlanPlant(repottingIntervalDays = 30)

        assertEquals(null, repotTaskOn(LocalDate.of(2026, 10, 1), plant))
    }

    @Test
    fun `a plan's task names the season of the input's hemisphere`() {
        // Sep 1 opens spring in the southern hemisphere.
        val start = LocalDate.of(2027, 9, 1)
        val plant = plant().copy(repotPlanSeasonStartAt = millis(start), repotPlanMadeAt = millis(today))
        val input = input(plants = listOf(plant), inputToday = start.plusDays(10))
            .copy(hemisphere = Hemisphere.SOUTHERN)

        val task = TodayQueueAggregator.build(input).tasks.single { it.kind == TodayCareKind.REPOT }

        assertEquals(TodayTaskBucket.Today, task.bucket)
        assertEquals(FertilizingSeason.SPRING, task.repotPlanSeason)
    }

    @Test
    fun `an interval repot carries no plan season and keeps its own overdue date`() {
        val plant = plant(repottingIntervalDays = 30)
        val dueDate = today.minusDays(30)
        val result = TodayQueueAggregator.build(
            input(plants = listOf(plant), logs = listOf(log(plant.id, CareType.REPOT, today.minusDays(60))))
        )

        val task = result.tasks.single { it.kind == TodayCareKind.REPOT }
        assertEquals(TodayTaskBucket.Overdue, task.bucket)
        assertEquals(millis(dueDate), task.dueAt)
        assertEquals(null, task.repotPlanSeason)
    }

    @Test
    fun `an in-season plan sorts after an overdue interval repot`() {
        val day = LocalDate.of(2027, 4, 15)
        val planned = springPlanPlant(id = 1L, name = "Aloe")
        val overdue = plant(id = 2L, name = "Zamia", repottingIntervalDays = 30)
        val result = TodayQueueAggregator.build(
            input(
                plants = listOf(planned, overdue),
                logs = listOf(log(overdue.id, CareType.REPOT, day.minusDays(40))),
                inputToday = day
            )
        )

        assertEquals(
            listOf("repot:2", "repot:1"),
            result.tasks.filter { it.kind == TodayCareKind.REPOT }.map { it.id }
        )
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
