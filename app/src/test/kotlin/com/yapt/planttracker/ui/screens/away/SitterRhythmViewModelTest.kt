package com.yapt.planttracker.ui.screens.away

import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sitter-rhythm step (#899, product ADR-0065): live evaluation, and the choice saved on the trip only. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SitterRhythmViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fixture: SitterFlowFixture

    @Before
    fun setUp() {
        fixture = SitterFlowFixture()
    }

    @After
    fun tearDown() = fixture.close()

    private fun TestScope.viewModel(): SitterRhythmViewModel {
        val vm = SitterRhythmViewModel(
            fixture.awayRepo,
            fixture.plantRepo,
            seasonalAmplitude = flowOf(0.0),
            dayChange = flowOf(fixture.today),
            hemisphere = { Hemisphere.NORTHERN },
            todayProvider = { fixture.today }
        )
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun `without a trip there is no step`() = runTest {
        assertEquals(SitterRhythmUiState.NoTrip, viewModel().uiState.value)
    }

    @Test
    fun `it evaluates the live plants for the trips first day`() = runTest {
        fixture.addTrip(fixture.today.plusDays(2), fixture.today.plusDays(12))
        fixture.addPlant("Basil", interval = 3)
        fixture.addPlant("Cactus", interval = 21)

        val ready = viewModel().uiState.value as SitterRhythmUiState.Ready

        assertEquals(fixture.today.plusDays(1), ready.evaluation.lastDayAtHome)
        val sevenDays = ready.evaluation.options.first { it.everyDays == 7 }
        assertEquals(listOf("Basil"), sevenDays.flagged.map { it.plant.name })
        assertEquals(4, ready.evaluation.recommendedEveryDays)
        assertNull(ready.savedEveryDays)
    }

    @Test
    fun `saving stores the rhythm on the trip and nothing else`() = runTest {
        val trip = fixture.addTrip(fixture.today.plusDays(2), fixture.today.plusDays(12))
        val plant = fixture.addPlant("Basil", interval = 3)
        val vm = viewModel()

        vm.save(10).join()

        val stored = fixture.awayRepo.getAll().single()
        assertEquals(10, stored.visitEveryDays)
        assertEquals(trip.firstDay, stored.firstDay)
        assertEquals(trip.lastDay, stored.lastDay)
        assertEquals(plant, fixture.plantRepo.getPlantById(plant.id).first())
        assertEquals(10, (vm.uiState.value as SitterRhythmUiState.Ready).savedEveryDays)
    }

    @Test
    fun `a rhythm that is not on offer is ignored`() = runTest {
        fixture.addTrip(fixture.today.plusDays(2), fixture.today.plusDays(12))
        val vm = viewModel()

        vm.save(6).join()

        assertNull(fixture.awayRepo.getAll().single().visitEveryDays)
    }
}
