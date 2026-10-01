package com.yapt.planttracker.domain.repotting

import app.cash.turbine.test
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.util.toStartOfDayMillis
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RepottingOverviewFlowTest {

    private val today = LocalDate.of(2026, 9, 30)

    private fun plant(id: Long, createdAt: LocalDate = LocalDate.of(2020, 1, 1), archivedAt: Long? = null) = Plant(
        id = id,
        name = "Plant $id",
        createdAt = createdAt.toStartOfDayMillis(),
        archivedAt = archivedAt
    )

    @Test
    fun `combines plants, last repots, the threshold and the day through the builder`() = runTest {
        val snapshot = observeRepottingOverview(
            plants = flowOf(listOf(plant(1), plant(2))),
            lastRepotAtByPlantId = flowOf(mapOf(2L to LocalDate.of(2026, 1, 1).toStartOfDayMillis())),
            threshold = flowOf(RepottingOverviewThreshold.TWO_YEARS),
            today = flowOf(today)
        ).first()

        assertEquals(RepottingOverviewThreshold.TWO_YEARS, snapshot.threshold)
        assertEquals(listOf(1L), snapshot.overview.items.map { it.plant.id })
        assertTrue(snapshot.hasActivePlants)
    }

    @Test
    fun `reports no active plants when there are none or only archived ones`() = runTest {
        val none = observeRepottingOverview(
            plants = flowOf(emptyList()),
            lastRepotAtByPlantId = flowOf(emptyMap()),
            threshold = flowOf(RepottingOverviewThreshold.NEVER),
            today = flowOf(today)
        ).first()
        val archivedOnly = observeRepottingOverview(
            flowOf(listOf(plant(1, archivedAt = 5L))),
            flowOf(emptyMap()),
            flowOf(RepottingOverviewThreshold.NEVER),
            flowOf(today)
        ).first()

        assertFalse(none.hasActivePlants)
        assertFalse(archivedOnly.hasActivePlants)
        assertTrue(archivedOnly.overview.items.isEmpty())
    }

    @Test
    fun `re-evaluates when any input changes, including the day`() = runTest {
        val plants = MutableStateFlow(listOf(plant(1, createdAt = LocalDate.of(2024, 10, 1))))
        val threshold = MutableStateFlow(RepottingOverviewThreshold.TWO_YEARS)
        val day = MutableSharedFlow<LocalDate>(replay = 1)
        day.tryEmit(LocalDate.of(2026, 9, 30))

        observeRepottingOverview(plants, MutableStateFlow(emptyMap()), threshold, day).test {
            assertEquals(0, awaitItem().overview.count)

            day.emit(LocalDate.of(2026, 10, 1))
            assertEquals(1, awaitItem().overview.count)

            threshold.value = RepottingOverviewThreshold.THREE_YEARS
            val three = awaitItem()
            assertEquals(RepottingOverviewThreshold.THREE_YEARS, three.threshold)
            assertEquals(0, three.overview.count)

            plants.value = plants.value + plant(2)
            assertEquals(1, awaitItem().overview.count)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
