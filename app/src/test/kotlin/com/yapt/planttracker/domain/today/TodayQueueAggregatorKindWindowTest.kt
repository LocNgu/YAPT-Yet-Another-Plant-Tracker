package com.yapt.planttracker.domain.today

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import com.yapt.planttracker.domain.schedule.Hemisphere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The per-kind window (#931, product ADR-0069): on Care's default path watering shows Overdue / Today / Tomorrow
 * and every other kind only Overdue / Today, while an explicitly widened horizon bounds every kind alike.
 */
class TodayQueueAggregatorKindWindowTest {

    private val today = LocalDate.of(2026, 9, 27)

    private fun millis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun upcoming(offset: Long = 1L) = TodayTaskBucket.Upcoming(today.plusDays(offset).toEpochDay())

    @Suppress("LongParameterList")
    private fun plant(
        id: Long = 1L,
        wateringIntervalDays: Int? = null,
        fertilizingIntervalDays: Int? = null,
        repottingIntervalDays: Int? = null,
        useLiquidFertilizer: Boolean = false,
        createdAt: Long = millis(today.minusDays(90))
    ) = Plant(
        id = id,
        name = "Plant $id",
        wateringIntervalDays = wateringIntervalDays,
        fertilizingIntervalDays = fertilizingIntervalDays,
        repottingIntervalDays = repottingIntervalDays,
        useLiquidFertilizer = useLiquidFertilizer,
        createdAt = createdAt,
        updatedAt = createdAt,
        pinIntervalToBase = true
    )

    private fun log(plantId: Long, type: CareType, date: LocalDate) =
        CareLog(plantId = plantId, careType = type, loggedAt = millis(date))

    @Suppress("LongParameterList")
    private fun input(
        plants: List<Plant>,
        logs: List<CareLog> = emptyList(),
        reminders: List<CustomReminder> = emptyList(),
        issues: List<PlantIssue> = emptyList(),
        photoEnabled: Boolean = false,
        horizonEnd: LocalDate? = null
    ): TodayQueueInput {
        val base = TodayQueueInput(
            plants = plants,
            careLogs = logs,
            customReminders = reminders,
            issues = issues,
            photos = emptyList(),
            today = today,
            seasonalAmplitude = 0.0,
            photoReminderEnabled = photoEnabled,
            hemisphere = Hemisphere.NORTHERN
        )
        return if (horizonEnd == null) base.copy(nonWateringHorizonEnd = today) else base.copy(horizonEnd = horizonEnd)
    }

    private fun defaultPath(
        plants: List<Plant>,
        logs: List<CareLog> = emptyList(),
        reminders: List<CustomReminder> = emptyList(),
        issues: List<PlantIssue> = emptyList(),
        photoEnabled: Boolean = false
    ) = TodayQueueAggregator.build(input(plants, logs, reminders, issues, photoEnabled)).tasks

    @Suppress("LongParameterList")
    private fun widened(
        plants: List<Plant>,
        logs: List<CareLog> = emptyList(),
        reminders: List<CustomReminder> = emptyList(),
        issues: List<PlantIssue> = emptyList(),
        photoEnabled: Boolean = false,
        horizonEnd: LocalDate
    ) = TodayQueueAggregator.build(input(plants, logs, reminders, issues, photoEnabled, horizonEnd)).tasks

    private data class KindCase(
        val kind: TodayCareKind,
        val plants: List<Plant>,
        val logs: List<CareLog>,
        val reminders: List<CustomReminder> = emptyList(),
        val issues: List<PlantIssue> = emptyList(),
        val photoEnabled: Boolean = false
    )

    private fun nonWateringCase(kind: TodayCareKind, dueOffset: Long): KindCase {
        val due = today.plusDays(dueOffset)
        return when (kind) {
            TodayCareKind.FERTILIZE -> KindCase(
                kind,
                listOf(plant(fertilizingIntervalDays = 10)),
                listOf(log(1L, CareType.FERTILIZE, due.minusDays(10)))
            )
            TodayCareKind.REPOT -> KindCase(
                kind,
                listOf(plant(repottingIntervalDays = 30)),
                listOf(log(1L, CareType.REPOT, due.minusDays(30)))
            )
            TodayCareKind.CUSTOM_REMINDER -> KindCase(
                kind,
                listOf(plant()),
                emptyList(),
                reminders = listOf(reminder(due))
            )
            TodayCareKind.ISSUE_TREATMENT -> KindCase(
                kind,
                listOf(plant()),
                emptyList(),
                reminders = listOf(reminder(due)),
                issues = listOf(PlantIssue(id = 10L, plantId = 1L, name = "Mites", linkedReminderId = 7L))
            )
            TodayCareKind.PHOTO -> KindCase(
                kind,
                listOf(plant(createdAt = millis(due.minusDays(PhotoReminderPolicy.PHOTO_REMINDER_INTERVAL_DAYS)))),
                emptyList(),
                photoEnabled = true
            )
            else -> error("not a non-watering kind: $kind")
        }
    }

    private fun reminder(due: LocalDate) = CustomReminder(
        id = 7L,
        plantId = 1L,
        name = "Neem",
        intervalDays = 5,
        lastDoneAt = millis(due.minusDays(5)),
        createdAt = millis(today.minusDays(60))
    )

    private val nonWateringKinds = listOf(
        TodayCareKind.FERTILIZE,
        TodayCareKind.REPOT,
        TodayCareKind.CUSTOM_REMINDER,
        TodayCareKind.ISSUE_TREATMENT,
        TodayCareKind.PHOTO
    )

    private fun tasksOf(case: KindCase, build: (KindCase) -> List<TodayCareTask>) =
        build(case).filter { it.kind == case.kind }

    private fun onDefaultPath(case: KindCase) =
        defaultPath(case.plants, case.logs, case.reminders, case.issues, case.photoEnabled)

    @Test
    fun `default horizon end is tomorrow and the non watering bound shares it unless set`() {
        val bare = TodayQueueInput(
            plants = emptyList(),
            careLogs = emptyList(),
            customReminders = emptyList(),
            issues = emptyList(),
            photos = emptyList(),
            today = today,
            seasonalAmplitude = 0.0,
            photoReminderEnabled = false
        )

        assertEquals(today.plusDays(1), bare.horizonEnd)
        assertNull(bare.nonWateringHorizonEnd)
        assertEquals(today.plusDays(1), bare.effectiveNonWateringHorizonEnd)
        assertEquals(today.plusDays(9), bare.copy(horizonEnd = today.plusDays(9)).effectiveNonWateringHorizonEnd)
        assertEquals(today, bare.copy(nonWateringHorizonEnd = today).effectiveNonWateringHorizonEnd)
    }

    @Test
    fun `on the default path watering due tomorrow is upcoming and due the day after is absent`() {
        val tomorrowPlant = plant(id = 1L, wateringIntervalDays = 3)
        val laterPlant = plant(id = 2L, wateringIntervalDays = 4)
        val logs = listOf(
            log(1L, CareType.WATER, today.minusDays(2)),
            log(2L, CareType.WATER, today.minusDays(2))
        )

        val tasks = defaultPath(listOf(tomorrowPlant, laterPlant), logs)

        assertEquals(mapOf("water:1" to upcoming()), tasks.associate { it.id to it.bucket })
    }

    @Test
    fun `on the default path every non watering kind due tomorrow is absent`() {
        for (kind in nonWateringKinds) {
            assertTrue("$kind due tomorrow", tasksOf(nonWateringCase(kind, 1L), ::onDefaultPath).isEmpty())
        }
    }

    @Test
    fun `on the default path every non watering kind is today when due today and overdue before`() {
        for (kind in nonWateringKinds) {
            assertEquals(
                "$kind due today",
                listOf(TodayTaskBucket.Today),
                tasksOf(nonWateringCase(kind, 0L), ::onDefaultPath).map { it.bucket }
            )
            assertEquals(
                "$kind due yesterday",
                listOf(TodayTaskBucket.Overdue),
                tasksOf(nonWateringCase(kind, -1L), ::onDefaultPath).map { it.bucket }
            )
        }
    }

    @Test
    fun `a repot plan whose season starts tomorrow is not on the default path`() {
        val start = today.plusDays(1)
        val plan = plant().copy(repotPlanSeasonStartAt = millis(start), repotPlanMadeAt = millis(today))

        assertTrue(defaultPath(listOf(plan)).isEmpty())
        assertEquals(
            upcoming(),
            widened(listOf(plan), horizonEnd = today.plusDays(1)).single().bucket
        )
    }

    @Test
    fun `liquid plant with watering tomorrow and fertilizing today is one combined task in tomorrow`() {
        val plant = plant(useLiquidFertilizer = true, wateringIntervalDays = 5, fertilizingIntervalDays = 10)
        val logs = listOf(
            log(plant.id, CareType.WATER, today.minusDays(4)),
            log(plant.id, CareType.FERTILIZE, today.minusDays(10))
        )

        val task = defaultPath(listOf(plant), logs).single()

        assertEquals(TodayCareKind.WATER_AND_FERTILIZE, task.kind)
        assertEquals(upcoming(), task.bucket)
    }

    @Test
    fun `liquid plant with watering and fertilizing both tomorrow is a plain watering task`() {
        val plant = plant(useLiquidFertilizer = true, wateringIntervalDays = 5, fertilizingIntervalDays = 10)
        val logs = listOf(
            log(plant.id, CareType.WATER, today.minusDays(4)),
            log(plant.id, CareType.FERTILIZE, today.minusDays(9))
        )

        val task = defaultPath(listOf(plant), logs).single()

        assertEquals(TodayCareKind.WATER, task.kind)
        assertEquals(upcoming(), task.bucket)
    }

    @Test
    fun `liquid plant with fertilizing today and watering two days out has no task`() {
        val plant = plant(useLiquidFertilizer = true, wateringIntervalDays = 5, fertilizingIntervalDays = 10)
        val logs = listOf(
            log(plant.id, CareType.WATER, today.minusDays(3)),
            log(plant.id, CareType.FERTILIZE, today.minusDays(10))
        )

        assertTrue(defaultPath(listOf(plant), logs).isEmpty())
    }

    @Test
    fun `a widened horizon bounds every kind and the per kind window never clips it`() {
        val horizon = today.plusDays(14)
        for (kind in nonWateringKinds) {
            val case = nonWateringCase(kind, 14L)
            val tasks = tasksOf(case) { widened(it.plants, it.logs, it.reminders, it.issues, it.photoEnabled, horizon) }

            assertEquals("$kind due on the horizon end", listOf(upcoming(14L)), tasks.map { it.bucket })
            assertTrue(
                "$kind due after the horizon end",
                tasksOf(nonWateringCase(kind, 15L)) {
                    widened(it.plants, it.logs, it.reminders, it.issues, it.photoEnabled, horizon)
                }.isEmpty()
            )
        }
        val fern = plant(wateringIntervalDays = 14)
        val watering = widened(listOf(fern), listOf(log(fern.id, CareType.WATER, today)), horizonEnd = horizon)
        assertEquals(listOf(upcoming(14L)), watering.map { it.bucket })
    }

    @Test
    fun `a directly constructed input with a widened horizon includes every kind`() {
        val horizon = today.plusDays(14)
        for (kind in nonWateringKinds) {
            val case = nonWateringCase(kind, 14L)
            val direct = TodayQueueInput(
                plants = case.plants,
                careLogs = case.logs,
                customReminders = case.reminders,
                issues = case.issues,
                photos = emptyList(),
                today = today,
                seasonalAmplitude = 0.0,
                photoReminderEnabled = case.photoEnabled,
                hemisphere = Hemisphere.NORTHERN,
                horizonEnd = horizon
            )

            val tasks = TodayQueueAggregator.build(direct).tasks.filter { it.kind == kind }

            assertEquals("$kind", listOf(upcoming(14L)), tasks.map { it.bucket })
        }
    }
}
