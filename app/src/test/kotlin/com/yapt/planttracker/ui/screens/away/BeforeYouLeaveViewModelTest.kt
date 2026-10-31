package com.yapt.planttracker.ui.screens.away

import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Before you leave (#899, product ADR-0065): the lists, the one bulk log, and what the log does not do; and the
 * plants brought forward for a trip they would not otherwise last (#969, product ADR-0076).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BeforeYouLeaveViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fixture: SitterFlowFixture

    @Before
    fun setUp() {
        fixture = SitterFlowFixture()
    }

    @After
    fun tearDown() = fixture.close()

    private fun TestScope.viewModel(): BeforeYouLeaveViewModel {
        val vm = BeforeYouLeaveViewModel(
            fixture.awayRepo,
            fixture.plantRepo,
            fixture.careLogRepo,
            fixture.useCase,
            seasonalAmplitude = flowOf(0.0),
            dayChange = flowOf(fixture.today)
        )
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
        return vm
    }

    private fun BeforeYouLeaveViewModel.ready() = uiState.value as BeforeYouLeaveUiState.Ready

    @Test
    fun `it is only available on the last day at home`() = runTest {
        fixture.addTrip(fixture.today.plusDays(3), fixture.today.plusDays(10))

        assertEquals(BeforeYouLeaveUiState.NotAvailable, viewModel().uiState.value)
    }

    @Test
    fun `without a trip there is nothing to offer`() = runTest {
        assertEquals(BeforeYouLeaveUiState.NotAvailable, viewModel().uiState.value)
    }

    @Test
    fun `water today is ticked and check the soil is not`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(1), today.plusDays(10), visitEveryDays = 7)
        fixture.addPlant("Due now", interval = 7, lastWatered = today.minusDays(7))
        fixture.addPlant("Due later", interval = 7, lastWatered = today.minusDays(2))
        fixture.addPlant("Far off", interval = 30, lastWatered = today)

        val ready = viewModel().ready()

        assertEquals(listOf("Due now"), ready.waterToday.map { it.plant.name })
        assertTrue(ready.waterToday.all { it.ticked })
        assertEquals(listOf("Due later"), ready.checkTheSoil.map { it.plant.name })
        assertTrue(ready.checkTheSoil.none { it.ticked })
        assertEquals(1, ready.canWaitCount)
        assertEquals(today.plusDays(7), ready.firstVisit)
    }

    @Test
    fun `one call logs exactly the ticked plants`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(1), today.plusDays(10), visitEveryDays = 7)
        val due = fixture.addPlant("Due now", interval = 7, lastWatered = today.minusDays(7))
        val soil = fixture.addPlant("Due later", interval = 7, lastWatered = today.minusDays(2))
        val vm = viewModel()

        vm.logTicked().join()

        assertEquals(2, fixture.waterLogs(due.id).size)
        assertEquals(1, fixture.waterLogs(soil.id).size)
    }

    @Test
    fun `a plant ticked from check the soil is logged and an unticked one is not`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(1), today.plusDays(10), visitEveryDays = 7)
        val due = fixture.addPlant("Due now", interval = 7, lastWatered = today.minusDays(7))
        val soil = fixture.addPlant("Due later", interval = 7, lastWatered = today.minusDays(2))
        val vm = viewModel()
        val events = mutableListOf<BeforeYouLeaveEvent>()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events += it } }

        vm.toggle(due.id)
        vm.toggle(soil.id)
        vm.logTicked().join()

        assertEquals(1, fixture.waterLogs(due.id).size)
        assertEquals(2, fixture.waterLogs(soil.id).size)
        assertEquals(listOf(BeforeYouLeaveEvent.Logged(logged = 1, skipped = 0)), events)
    }

    @Test
    fun `logging with nothing ticked writes nothing`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(1), today.plusDays(10), visitEveryDays = 7)
        val due = fixture.addPlant("Due now", interval = 7, lastWatered = today.minusDays(7))
        val vm = viewModel()
        vm.toggle(due.id)

        vm.logTicked().join()

        assertEquals(1, fixture.waterLogs(due.id).size)
    }

    @Test
    fun `a plant already watered today is skipped by the duplicate guard`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(1), today.plusDays(10), visitEveryDays = 7)
        // A one-day interval watered today is due tomorrow, so it is on the list even though it was watered.
        val daily = fixture.addPlant("Daily", interval = 1, lastWatered = today)
        val vm = viewModel()
        val events = mutableListOf<BeforeYouLeaveEvent>()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events += it } }
        assertEquals(listOf("Daily"), vm.ready().waterToday.map { it.plant.name })

        vm.logTicked().join()

        assertEquals(1, fixture.waterLogs(daily.id).size)
        assertEquals(listOf(BeforeYouLeaveEvent.Logged(logged = 0, skipped = 1)), events)
    }

    @Test
    fun `the logs carry no reason and stay out of learning like Just my timing`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(1), today.plusDays(10), visitEveryDays = 7)
        // Watered five days ago on an 8-day interval: due in three days, so it is listed, and early.
        val early = fixture.addPlant("Early", interval = 8, lastWatered = today.minusDays(5))
        val before = fixture.plantRepo.getPlantById(early.id).first()!!
        val vm = viewModel()
        assertEquals(listOf("Early"), vm.ready().waterToday.map { it.plant.name })

        vm.logTicked().join()

        val log = fixture.waterLogs(early.id).maxByOrNull { it.loggedAt }!!
        assertNull(log.wateringFeedback)
        val after = fixture.plantRepo.getPlantById(early.id).first()!!
        assertEquals(before.wateringIntervalDays, after.wateringIntervalDays)
        assertEquals(
            1,
            fixture.adjustments.getByTrigger(early.id, WateringAdjustmentTrigger.WATER_NOT_ATTRIBUTED).size
        )
        assertTrue(fixture.postWateringReminders.size <= 1)
    }

    @Test
    fun `check the soil also lists a plant that would come due during the trip`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(1), today.plusDays(21), visitEveryDays = 4)
        // 15 of 21 days in: due in six days, after the first visit (day 4) and inside the 21-day trip.
        fixture.addPlant("Long haul", interval = 21, lastWatered = today.minusDays(15))
        // 14 of 21 days in is short of 70%, so it can wait.
        fixture.addPlant("Not far enough", interval = 21, lastWatered = today.minusDays(14))
        fixture.addPlant("Due now", interval = 4, lastWatered = today.minusDays(4))

        val ready = viewModel().ready()

        assertEquals(listOf("Due now"), ready.waterToday.map { it.plant.name })
        assertEquals(listOf("Long haul"), ready.checkTheSoil.map { it.plant.name })
        assertEquals(today.plusDays(6), ready.checkTheSoil.single().dueDate)
        assertTrue(ready.checkTheSoil.none { it.ticked })
        assertEquals(1, ready.canWaitCount)
    }

    @Test
    fun `a plant brought forward is logged only when the owner ticks it`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(1), today.plusDays(21), visitEveryDays = 4)
        val longHaul = fixture.addPlant("Long haul", interval = 21, lastWatered = today.minusDays(15))
        val vm = viewModel()
        val events = mutableListOf<BeforeYouLeaveEvent>()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events += it } }

        vm.logTicked().join()
        assertEquals(1, fixture.waterLogs(longHaul.id).size)

        vm.toggle(longHaul.id)
        vm.logTicked().join()

        assertEquals(2, fixture.waterLogs(longHaul.id).size)
        assertEquals(listOf(BeforeYouLeaveEvent.Logged(logged = 1, skipped = 0)), events)
    }

    @Test
    fun `a plant already in check the soil is listed once`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(1), today.plusDays(21), visitEveryDays = 7)
        fixture.addPlant("Long haul", interval = 21, lastWatered = today.minusDays(15))

        val ready = viewModel().ready()

        assertEquals(listOf("Long haul"), ready.checkTheSoil.map { it.plant.name })
        assertEquals(0, ready.canWaitCount)
    }

    @Test
    fun `a trip longer than the plant's interval adds nothing`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(1), today.plusDays(22), visitEveryDays = 4)
        fixture.addPlant("Long haul", interval = 21, lastWatered = today.minusDays(15))

        val ready = viewModel().ready()

        assertTrue(ready.checkTheSoil.isEmpty())
        assertEquals(1, ready.canWaitCount)
    }
}
