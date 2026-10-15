package com.yapt.planttracker.ui.screens.watering

import app.cash.turbine.test
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.watering.WateringOverviewGroup
import com.yapt.planttracker.util.MainDispatcherRule
import com.yapt.planttracker.util.toStartOfDayMillis
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class WateringOverviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepository = mockk<PlantRepository>()
    private val careLogRepository = mockk<CareLogRepository>()

    private val plants = MutableStateFlow<List<Plant>>(emptyList())
    private val lastWatered = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val amplitude = MutableStateFlow(0.0)
    private val summer = LocalDate.of(2026, 7, 4).toStartOfDayMillis() + TimeUnit.HOURS.toMillis(12)
    private val winter = LocalDate.of(2027, 1, 5).toStartOfDayMillis() + TimeUnit.HOURS.toMillis(12)

    @Before
    fun setUp() {
        every { plantRepository.getAllPlants() } returns plants
        every { careLogRepository.observeLastCareAtByPlant(CareType.WATER) } returns lastWatered
    }

    private fun plant(id: Long, interval: Int? = 6) = Plant(
        id = id,
        name = "Plant $id",
        wateringIntervalDays = interval,
        wateringBaseIntervalDays = interval?.toDouble()
    )

    private fun buildVm(
        dayChange: Flow<LocalDate> = flowOf(LocalDate.of(2026, 7, 4)),
        nowProvider: () -> Long = { summer }
    ) = WateringOverviewViewModel(
        plantRepository = plantRepository,
        careLogRepository = careLogRepository,
        seasonalAmplitude = amplitude,
        dayChange = dayChange,
        nowProvider = nowProvider,
        hemisphere = { Hemisphere.NORTHERN }
    )

    private fun TestScope.keepUiStateHot(vm: WateringOverviewViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
    }

    private fun WateringOverviewViewModel.ready() = uiState.value as WateringOverviewUiState.Ready

    private fun WateringOverviewUiState.Ready.groups() = overview.sections.map { it.group }

    @Test
    fun `starts loading then reports the overview`() = runTest {
        plants.value = listOf(plant(1), plant(2, interval = null))
        val vm = buildVm()

        vm.uiState.test {
            var state = awaitItem()
            if (state is WateringOverviewUiState.Loading) state = awaitItem()
            state as WateringOverviewUiState.Ready
            assertEquals(
                listOf(WateringOverviewGroup.EVERY_4_7_DAYS, WateringOverviewGroup.NO_SCHEDULE),
                state.groups()
            )
            assertTrue(state.hasActivePlants)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no plants reports no active plants`() = runTest {
        val vm = buildVm()
        keepUiStateHot(vm)

        assertFalse(vm.ready().hasActivePlants)
        assertTrue(vm.ready().overview.sections.isEmpty())
    }

    @Test
    fun `the page rolls over when the day changes and the seasonal interval moves a plant between bands`() = runTest {
        plants.value = listOf(plant(1))
        amplitude.value = 0.5
        var now = summer
        val ticker = MutableSharedFlow<LocalDate>(replay = 1)
        ticker.tryEmit(LocalDate.of(2026, 7, 4))
        val vm = buildVm(dayChange = ticker, nowProvider = { now })
        keepUiStateHot(vm)
        assertEquals(listOf(WateringOverviewGroup.EVERY_1_3_DAYS), vm.ready().groups())

        now = winter
        ticker.emit(LocalDate.of(2027, 1, 5))

        assertEquals(listOf(WateringOverviewGroup.EVERY_8_14_DAYS), vm.ready().groups())
    }

    @Test
    fun `a change to the seasonal amplitude re-evaluates without a restart`() = runTest {
        plants.value = listOf(plant(1))
        amplitude.value = 0.5
        val vm = buildVm(nowProvider = { winter })
        keepUiStateHot(vm)
        assertEquals(listOf(WateringOverviewGroup.EVERY_8_14_DAYS), vm.ready().groups())

        amplitude.value = 0.0

        assertEquals(listOf(WateringOverviewGroup.EVERY_4_7_DAYS), vm.ready().groups())
    }

    @Test
    fun `a watering logged elsewhere moves the next due date in place`() = runTest {
        plants.value = listOf(plant(1))
        val vm = buildVm()
        keepUiStateHot(vm)
        assertEquals(summer, vm.ready().overview.sections.single().items.single().nextDueAt)

        lastWatered.value = mapOf(1L to summer)

        assertEquals(
            summer + TimeUnit.DAYS.toMillis(6),
            vm.ready().overview.sections.single().items.single().nextDueAt
        )
    }

    @Test
    fun `archiving a plant removes it from the page`() = runTest {
        plants.value = listOf(plant(1), plant(2))
        val vm = buildVm()
        keepUiStateHot(vm)

        plants.value = listOf(plant(1), plant(2).copy(archivedAt = 5L))

        assertEquals(listOf(1L), vm.ready().overview.sections.single().items.map { it.plant.id })
    }
}
