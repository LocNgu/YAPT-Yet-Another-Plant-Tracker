package com.yapt.planttracker.domain.watering

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
import java.util.concurrent.TimeUnit

class WateringOverviewFlowTest {

    private val summer = LocalDate.of(2026, 7, 4).toStartOfDayMillis() + TimeUnit.HOURS.toMillis(12)
    private val winter = LocalDate.of(2027, 1, 5).toStartOfDayMillis() + TimeUnit.HOURS.toMillis(12)

    private fun plant(id: Long, interval: Int? = 6, archivedAt: Long? = null) = Plant(
        id = id,
        name = "Plant $id",
        wateringIntervalDays = interval,
        wateringBaseIntervalDays = interval?.toDouble(),
        archivedAt = archivedAt
    )

    private fun WateringOverviewSnapshot.groups() = overview.sections.map { it.group }

    @Test
    fun `builds the overview from plants, last waterings and the amplitude`() = runTest {
        val snapshot = observeWateringOverview(
            plants = flowOf(listOf(plant(1), plant(2, interval = null))),
            lastWateredAtByPlantId = flowOf(emptyMap()),
            seasonalAmplitude = flowOf(0.0),
            dayChange = flowOf(Unit),
            nowProvider = { summer }
        ).first()

        assertEquals(
            listOf(WateringOverviewGroup.EVERY_4_7_DAYS, WateringOverviewGroup.NO_SCHEDULE),
            snapshot.groups()
        )
        assertTrue(snapshot.hasActivePlants)
    }

    @Test
    fun `reports no active plants when there are none or only archived ones`() = runTest {
        val none = observeWateringOverview(
            flowOf(emptyList()),
            flowOf(emptyMap()),
            flowOf(0.0),
            flowOf(Unit)
        ).first()
        val archivedOnly = observeWateringOverview(
            flowOf(listOf(plant(1, archivedAt = 5L))),
            flowOf(emptyMap()),
            flowOf(0.0),
            flowOf(Unit)
        ).first()

        assertFalse(none.hasActivePlants)
        assertFalse(archivedOnly.hasActivePlants)
        assertTrue(archivedOnly.overview.sections.isEmpty())
    }

    @Test
    fun `re-evaluates at a day change, a seasonal step and an amplitude change`() = runTest {
        var now = summer
        val plants = MutableStateFlow(listOf(plant(1)))
        val amplitude = MutableStateFlow(0.5)
        val dayChange = MutableSharedFlow<LocalDate>(replay = 1)
        dayChange.tryEmit(LocalDate.of(2026, 7, 4))

        observeWateringOverview(plants, MutableStateFlow(emptyMap()), amplitude, dayChange) { now }.test {
            assertEquals(listOf(WateringOverviewGroup.EVERY_1_3_DAYS), awaitItem().groups())

            now = winter
            dayChange.emit(LocalDate.of(2027, 1, 5))
            assertEquals(listOf(WateringOverviewGroup.EVERY_8_14_DAYS), awaitItem().groups())

            amplitude.value = 0.0
            assertEquals(listOf(WateringOverviewGroup.EVERY_4_7_DAYS), awaitItem().groups())

            plants.value = plants.value + plant(2, interval = null)
            assertEquals(
                listOf(WateringOverviewGroup.EVERY_4_7_DAYS, WateringOverviewGroup.NO_SCHEDULE),
                awaitItem().groups()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `re-evaluates when a watering is logged`() = runTest {
        val lastWatered = MutableStateFlow<Map<Long, Long>>(emptyMap())
        val dayChange = MutableSharedFlow<LocalDate>(replay = 1)
        dayChange.tryEmit(LocalDate.of(2026, 7, 4))

        observeWateringOverview(
            flowOf(listOf(plant(1))),
            lastWatered,
            flowOf(0.0),
            dayChange
        ) { summer }.test {
            val before = awaitItem().overview.sections.single().items.single().nextDueAt
            lastWatered.value = mapOf(1L to summer)
            val after = awaitItem().overview.sections.single().items.single().nextDueAt
            assertEquals(summer, before)
            assertEquals(summer + TimeUnit.DAYS.toMillis(6), after)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
