package com.yapt.planttracker.ui.screens.away

import com.yapt.planttracker.domain.away.AWAY_BANNER_LEAD_DAYS
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.time.LocalDate

/**
 * Before you leave (#899, product ADR-0065): the lists, the one bulk log, and what the log does not do; the
 * plants brought forward for a trip they would not otherwise last, and the read-only lead-in with its hold list in
 * the days before (#969, product ADR-0076).
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

    private fun TestScope.viewModel(
        dayChange: Flow<LocalDate> = flowOf(fixture.today)
    ): BeforeYouLeaveViewModel {
        val vm = BeforeYouLeaveViewModel(
            fixture.awayRepo,
            fixture.plantRepo,
            fixture.careLogRepo,
            fixture.useCase,
            seasonalAmplitude = flowOf(0.0),
            dayChange = dayChange
        )
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
        return vm
    }

    private fun BeforeYouLeaveViewModel.ready() = uiState.value as BeforeYouLeaveUiState.Ready

    private fun BeforeYouLeaveViewModel.leadIn() = uiState.value as BeforeYouLeaveUiState.LeadIn

    @Test
    fun `it is not available more than a week before the trip`() = runTest {
        fixture.addTrip(fixture.today.plusDays(AWAY_BANNER_LEAD_DAYS + 1), fixture.today.plusDays(20))

        assertEquals(BeforeYouLeaveUiState.NotAvailable, viewModel().uiState.value)
    }

    @Test
    fun `it is not available once the trip has started`() = runTest {
        fixture.addTrip(fixture.today.minusDays(2), fixture.today.plusDays(10))

        assertEquals(BeforeYouLeaveUiState.NotAvailable, viewModel().uiState.value)
    }

    @Test
    fun `it is not available on the trip's first day`() = runTest {
        fixture.addTrip(fixture.today, fixture.today.plusDays(10))

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

    // --- the lead-in window and its hold list (#969, product ADR-0076) ---

    @Test
    fun `the days before the last day at home are the lead-in, empty when nothing qualifies`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(3), today.plusDays(10))
        fixture.addPlant("Fern", interval = 7, lastWatered = today.minusDays(2))

        val leadIn = viewModel().leadIn()

        assertEquals(today, leadIn.today)
        assertEquals(today.plusDays(2), leadIn.lastDayAtHome)
        assertTrue(leadIn.holds.isEmpty())
    }

    @Test
    fun `the lead-in opens a week before the trip`() = runTest {
        fixture.addTrip(fixture.today.plusDays(AWAY_BANNER_LEAD_DAYS), fixture.today.plusDays(20))

        assertEquals(
            fixture.today.plusDays(AWAY_BANNER_LEAD_DAYS - 1),
            viewModel().leadIn().lastDayAtHome
        )
    }

    @Test
    fun `a hold whose due date is still ahead is listed with that date`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(5), today.plusDays(25))
        // Watered 20 days ago on a 21-day interval: due tomorrow, and only 3 days late once held to the last day.
        fixture.addPlant("Fern", interval = 21, lastWatered = today.minusDays(20))
        // 10 days ago is nowhere near due.
        fixture.addPlant("Cactus", interval = 21, lastWatered = today.minusDays(10))

        val holds = viewModel().leadIn().holds

        assertEquals(listOf("Fern"), holds.map { it.plant.name })
        assertEquals(today.plusDays(1), holds.single().dueDate)
        assertTrue(holds.single().dueDate > today)
    }

    @Test
    fun `an overdue hold is listed with its real due date`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(2), today.plusDays(22))
        fixture.addPlant("Fern", interval = 21, lastWatered = today.minusDays(23))

        val holds = viewModel().leadIn().holds

        assertEquals(listOf("Fern"), holds.map { it.plant.name })
        assertEquals(today.minusDays(2), holds.single().dueDate)
    }

    @Test
    fun `the state follows the day change from a week out to the last day at home and to the trip`() = runTest {
        val first = fixture.today.plusDays(AWAY_BANNER_LEAD_DAYS + 1)
        fixture.addTrip(first, first.plusDays(10))
        val dayChange = MutableSharedFlow<LocalDate>(replay = 1)
        val vm = viewModel(dayChange)
        val lastDay = first.minusDays(1)

        dayChange.emit(first.minusDays(AWAY_BANNER_LEAD_DAYS + 1))
        assertEquals(BeforeYouLeaveUiState.NotAvailable, vm.uiState.value)

        dayChange.emit(first.minusDays(AWAY_BANNER_LEAD_DAYS))
        assertEquals(lastDay, vm.leadIn().lastDayAtHome)

        dayChange.emit(first.minusDays(2))
        assertEquals(first.minusDays(2), vm.leadIn().today)

        dayChange.emit(lastDay)
        assertEquals(lastDay, vm.ready().today)

        dayChange.emit(first)
        assertEquals(BeforeYouLeaveUiState.NotAvailable, vm.uiState.value)
    }

    @Test
    fun `editing the trip mid lead-in moves the window`() = runTest {
        val today = fixture.today
        val trip = fixture.addTrip(today.plusDays(5), today.plusDays(25))
        val vm = viewModel()
        assertEquals(today.plusDays(4), vm.leadIn().lastDayAtHome)

        fixture.awayRepo.updatePeriod(trip.copy(firstDay = today.plusDays(3), lastDay = today.plusDays(23)))
        assertEquals(today.plusDays(2), vm.leadIn().lastDayAtHome)

        fixture.awayRepo.updatePeriod(trip.copy(firstDay = today.plusDays(10), lastDay = today.plusDays(30)))
        assertEquals(BeforeYouLeaveUiState.NotAvailable, vm.uiState.value)

        fixture.awayRepo.updatePeriod(trip.copy(firstDay = today.plusDays(1), lastDay = today.plusDays(21)))
        assertEquals(today, vm.ready().today)
    }

    @Test
    fun `cancelling the trip mid lead-in leaves nothing to show`() = runTest {
        val trip = fixture.addTrip(fixture.today.plusDays(5), fixture.today.plusDays(25))
        val vm = viewModel()
        assertTrue(vm.uiState.value is BeforeYouLeaveUiState.LeadIn)

        fixture.awayRepo.deletePeriod(trip)

        assertEquals(BeforeYouLeaveUiState.NotAvailable, vm.uiState.value)
    }

    @Test
    fun `a held plant watered during the lead-in drops out of the list`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(5), today.plusDays(25))
        val fern = fixture.addPlant("Fern", interval = 21, lastWatered = today.minusDays(20))
        val vm = viewModel()
        assertEquals(listOf(fern.id), vm.leadIn().holds.map { it.plant.id })

        fixture.careLogRepo.addLog(
            CareLog(plantId = fern.id, careType = CareType.WATER, loggedAt = fixture.millis(today))
        )

        assertTrue(vm.leadIn().holds.isEmpty())
    }

    @Test
    fun `nothing is ticked, logged or written during the lead-in`() = runTest {
        val today = fixture.today
        fixture.addTrip(today.plusDays(5), today.plusDays(25))
        val fern = fixture.addPlant("Fern", interval = 21, lastWatered = today.minusDays(20))
        val dayChange = MutableSharedFlow<LocalDate>(replay = 1)
        val vm = viewModel(dayChange)
        val events = mutableListOf<BeforeYouLeaveEvent>()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events += it } }
        dayChange.emit(today)
        val plantsBefore = fixture.plantRepo.getAllPlants().first()
        val logsBefore = fixture.careLogRepo.getAllLogs().first()
        assertEquals(listOf(fern.id), vm.leadIn().holds.map { it.plant.id })

        vm.toggle(fern.id)
        vm.logTicked().join()
        dayChange.emit(today.plusDays(1))
        dayChange.emit(today.plusDays(2))

        assertEquals(listOf(fern.id), vm.leadIn().holds.map { it.plant.id })
        assertEquals(plantsBefore, fixture.plantRepo.getAllPlants().first())
        assertEquals(logsBefore, fixture.careLogRepo.getAllLogs().first())
        assertTrue(events.isEmpty())
    }
}
