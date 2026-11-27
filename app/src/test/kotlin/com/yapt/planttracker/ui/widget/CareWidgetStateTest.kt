package com.yapt.planttracker.ui.widget

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareSection
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayQueueAggregator
import com.yapt.planttracker.domain.today.TodayQueueInput
import com.yapt.planttracker.domain.today.TodayQueueSnapshot
import com.yapt.planttracker.domain.today.TodayTaskBucket
import com.yapt.planttracker.domain.today.TodayWateringGroup
import com.yapt.planttracker.domain.today.careTypeSections
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Queue → widget mapping (#923, product ADR-0088): watering only, counts and rows identical to Care. */
class CareWidgetStateTest {

    private val today = LocalDate.of(2026, 11, 21)

    private fun at(date: LocalDate): Long = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun plant(id: Long, name: String = "Plant $id", hiddenAt: Long? = null, archivedAt: Long? = null) =
        Plant(
            id = id,
            name = name,
            wateringIntervalDays = INTERVAL,
            hiddenAt = hiddenAt,
            archivedAt = archivedAt,
            createdAt = at(today.minusDays(90)),
            updatedAt = at(today.minusDays(90)),
            pinIntervalToBase = true
        )

    private fun wateredDaysAgo(plantId: Long, days: Long) =
        CareLog(plantId = plantId, careType = CareType.WATER, loggedAt = at(today.minusDays(days)))

    private fun snapshot(plants: List<Plant>, logs: List<CareLog>): TodayQueueSnapshot =
        TodayQueueAggregator.build(
            TodayQueueInput(
                plants = plants,
                careLogs = logs,
                customReminders = emptyList(),
                issues = emptyList(),
                photos = emptyList(),
                today = today,
                seasonalAmplitude = 0.0,
                photoReminderEnabled = false,
                hemisphere = Hemisphere.NORTHERN,
                nonWateringHorizonEnd = today
            )
        )

    private fun task(plant: Plant, kind: TodayCareKind, bucket: TodayTaskBucket, id: String = "${kind}_${plant.id}") =
        TodayCareTask(id = id, plant = plant, kind = kind, dueAt = at(today), bucket = bucket)

    private fun CareWidgetState.watering() = this as CareWidgetState.Watering

    @Test
    fun `no snapshot or no visible plants is the first-use state`() {
        assertEquals(CareWidgetState.NoPlants, careWidgetState(null))
        assertEquals(
            CareWidgetState.NoPlants,
            careWidgetState(TodayQueueSnapshot(activePlantCount = 0, tasks = emptyList()))
        )
    }

    @Test
    fun `groups follow Care's Overdue, Today, Tomorrow order and only overdue and today are counted`() {
        val overdue = plant(1, "Fern")
        val dueToday = plant(2, "Monstera")
        val tomorrow = plant(3, "Calathea")
        val later = plant(4, "Cactus")
        val state = careWidgetState(
            snapshot(
                plants = listOf(tomorrow, later, dueToday, overdue),
                logs = listOf(
                    wateredDaysAgo(1, INTERVAL + 2L),
                    wateredDaysAgo(2, INTERVAL.toLong()),
                    wateredDaysAgo(3, INTERVAL - 1L),
                    wateredDaysAgo(4, 1)
                )
            )
        ).watering()

        assertEquals(2, state.dueNowCount)
        assertEquals(
            listOf(TodayWateringGroup.OVERDUE, TodayWateringGroup.TODAY, TodayWateringGroup.TOMORROW),
            state.groups.map { it.group }
        )
        assertEquals(
            listOf(listOf("Fern"), listOf("Monstera"), listOf("Calathea")),
            state.groups.map { g -> g.rows.map { it.plantName } }
        )
        assertTrue(state.groups.flatMap { it.rows }.all { it.plantDetailTab == PlantDetailTab.WATER })
    }

    @Test
    fun `hidden and archived plants never appear`() {
        val state = careWidgetState(
            snapshot(
                plants = listOf(plant(1), plant(2, hiddenAt = at(today)), plant(3, archivedAt = at(today))),
                logs = (1L..3L).map { wateredDaysAgo(it, INTERVAL + 1L) }
            )
        ).watering()

        assertEquals(1, state.dueNowCount)
        assertEquals(listOf(1L), state.groups.flatMap { it.rows }.map { it.plantId })
    }

    @Test
    fun `only tomorrow's watering is the nothing-to-water state that still lists tomorrow`() {
        val state = careWidgetState(
            snapshot(plants = listOf(plant(1)), logs = listOf(wateredDaysAgo(1, INTERVAL - 1L)))
        ).watering()

        assertEquals(0, state.dueNowCount)
        assertEquals(listOf(TodayWateringGroup.TOMORROW), state.groups.map { it.group })
    }

    @Test
    fun `plants but nothing to water has no groups`() {
        val state = careWidgetState(
            snapshot(plants = listOf(plant(1)), logs = listOf(wateredDaysAgo(1, 0)))
        ).watering()

        assertEquals(0, state.dueNowCount)
        assertTrue(state.groups.isEmpty())
    }

    @Test
    fun `non-watering kinds are left out and the combined task opens the Fertilize tab`() {
        val a = plant(1, "Fern")
        val b = plant(2, "Monstera")
        val tasks = listOf(
            task(a, TodayCareKind.WATER_AND_FERTILIZE, TodayTaskBucket.Today),
            task(b, TodayCareKind.FERTILIZE, TodayTaskBucket.Today),
            task(b, TodayCareKind.REPOT, TodayTaskBucket.Overdue),
            task(b, TodayCareKind.PHOTO, TodayTaskBucket.Today),
            task(b, TodayCareKind.CUSTOM_REMINDER, TodayTaskBucket.Today),
            task(b, TodayCareKind.ISSUE_TREATMENT, TodayTaskBucket.Today)
        )
        val state = careWidgetState(TodayQueueSnapshot(activePlantCount = 2, tasks = tasks)).watering()

        assertEquals(1, state.dueNowCount)
        val rows = state.groups.flatMap { it.rows }
        assertEquals(listOf(TodayCareKind.WATER_AND_FERTILIZE), rows.map { it.kind })
        assertEquals(PlantDetailTab.FERTILIZE, rows.single().plantDetailTab)
    }

    @Test
    fun `count and rows match Care's watering sections`() {
        val plants = (1L..6L).map { plant(it) }
        val snap = snapshot(
            plants = plants,
            logs = plants.mapIndexed { i, p -> wateredDaysAgo(p.id, i.toLong() + 1) }
        )
        val careWatering = careTypeSections(snap.tasks).filter { it.section == TodayCareSection.WATERING }
        val state = careWidgetState(snap).watering()

        assertEquals(
            careWatering.filter { it.wateringGroup != TodayWateringGroup.TOMORROW }.sumOf { it.plantCount },
            state.dueNowCount
        )
        assertEquals(careWatering.map { it.wateringGroup }, state.groups.map { it.group })
        assertEquals(
            careWatering.map { s -> s.tasks.map { it.id } },
            state.groups.map { g -> g.rows.map { it.taskId } }
        )
    }

    private companion object {
        const val INTERVAL = 5
    }
}
