package com.yapt.planttracker.ui.screens.plantdetail

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.WateringScheduleMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSummaryRowsTest {

    private val last = 1_000L
    private val next = 2_000L

    private val wateringPlant = Plant(id = 1L, name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)

    private val fertilizingPlant = wateringPlant.copy(fertilizingIntervalDays = 30)

    private fun status(plant: Plant = wateringPlant) = PlantCareStatus(
        plant = plant,
        lastWateredAt = last,
        lastFertilizedAt = null,
        daysSinceLastWatering = null,
        nextWateringDueAt = next,
        isOverdue = false,
        isDueSoon = false,
        nextFertilizingDueAt = null,
        isFertilizingOverdue = false,
        isFertilizingDueSoon = false,
        totalCareLogs = 0
    )

    private fun rows(status: PlantCareStatus) = homeSummaryRows(status).associate { it.field to it.value }

    @Test
    fun `watering only plant shows just the two watering rows`() {
        assertEquals(
            mapOf(
                HomeSummaryField.LAST_WATERED to HomeSummaryValue.At(last),
                HomeSummaryField.NEXT_WATERING to HomeSummaryValue.At(next)
            ),
            rows(status())
        )
    }

    @Test
    fun `fertilizing rows appear only with a fertilizing interval`() {
        val result = rows(status(fertilizingPlant).copy(lastFertilizedAt = 500L, nextFertilizingDueAt = 3_000L))

        assertEquals(HomeSummaryValue.At(500L), result[HomeSummaryField.LAST_FERTILIZED])
        assertEquals(HomeSummaryValue.At(3_000L), result[HomeSummaryField.NEXT_FERTILIZING])
    }

    @Test
    fun `never watered and never fertilized read as never`() {
        val result = rows(status(fertilizingPlant).copy(lastWateredAt = null, nextFertilizingDueAt = 3_000L))

        assertEquals(HomeSummaryValue.Never, result[HomeSummaryField.LAST_WATERED])
        assertEquals(HomeSummaryValue.At(next), result[HomeSummaryField.NEXT_WATERING])
        assertEquals(HomeSummaryValue.Never, result[HomeSummaryField.LAST_FERTILIZED])
    }

    @Test
    fun `suspended dormant watering shows dormant but still shows last watered`() {
        val result = rows(
            status().copy(
                nextWateringDueAt = null,
                isDormant = true,
                wateringScheduleMode = WateringScheduleMode.DORMANT_SUSPENDED
            )
        )

        assertEquals(HomeSummaryValue.At(last), result[HomeSummaryField.LAST_WATERED])
        assertEquals(HomeSummaryValue.Dormant, result[HomeSummaryField.NEXT_WATERING])
    }

    @Test
    fun `dormant cadence shows the active cadence due date`() {
        val result = rows(
            status().copy(isDormant = true, wateringScheduleMode = WateringScheduleMode.DORMANT_CADENCE)
        )

        assertEquals(HomeSummaryValue.At(next), result[HomeSummaryField.NEXT_WATERING])
    }

    @Test
    fun `dormant fertilizing shows dormant`() {
        val result = rows(status(fertilizingPlant).copy(isDormant = true))

        assertEquals(HomeSummaryValue.Dormant, result[HomeSummaryField.NEXT_FERTILIZING])
    }

    @Test
    fun `no watering interval hides the next watering row`() {
        val plant = wateringPlant.copy(wateringIntervalDays = null)
        val result = rows(status(plant).copy(nextWateringDueAt = null))

        assertEquals(setOf(HomeSummaryField.LAST_WATERED), result.keys)
    }

    @Test
    fun `no watering interval during a dormancy window still hides the next watering row`() {
        val plant = wateringPlant.copy(wateringIntervalDays = null)
        val result = rows(
            status(plant).copy(
                nextWateringDueAt = null,
                wateringScheduleMode = WateringScheduleMode.DORMANT_SUSPENDED
            )
        )

        assertEquals(setOf(HomeSummaryField.LAST_WATERED), result.keys)
    }
}
