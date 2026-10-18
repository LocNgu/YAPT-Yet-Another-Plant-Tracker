package com.yapt.planttracker.ui.screens.away

import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.SitterVisitState
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Confirming a sitter visit from Care and Settings (#900, product ADR-0065), on slice 4's checklist and write path. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConfirmVisitViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fixture: SitterFlowFixture
    private lateinit var today: LocalDate
    private lateinit var trip: AwayPeriod

    @Before
    fun setUp() {
        fixture = SitterFlowFixture()
        today = fixture.today
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun TestScope.viewModel(): ConfirmVisitViewModel {
        val vm = ConfirmVisitViewModel(fixture.awayRepo, fixture.plantRepo, fixture.useCase)
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
        return vm
    }

    private suspend fun endedTrip() {
        trip = fixture.addTrip(today.minusDays(10), today.minusDays(1))
    }

    private suspend fun waterLogDays(plant: Plant) = fixture.waterLogs(plant.id).map {
        Instant.ofEpochMilli(it.loggedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    @Test
    fun `nothing is open until a visit is chosen`() = runTest {
        endedTrip()
        val a = fixture.addPlant("Aloe")
        fixture.addVisit(trip, today.minusDays(5), SitterVisitState.PLANNED, a to null)

        assertNull(viewModel().uiState.value)
    }

    @Test
    fun `opening a visit ticks every plant, sorted by name`() = runTest {
        endedTrip()
        val b = fixture.addPlant("Basil")
        val a = fixture.addPlant("Aloe")
        val visit = fixture.addVisit(trip, today.minusDays(5), SitterVisitState.PLANNED, b to null, a to null)
        val vm = viewModel()

        vm.open(visit.id)

        val confirm = vm.uiState.value!!
        assertEquals(listOf("Aloe", "Basil"), confirm.rows.map { it.plant.name })
        assertTrue(confirm.rows.all { it.ticked })
    }

    @Test
    fun `continue logs exactly the ticked plants on the visit day and stores the visit as confirmed`() = runTest {
        endedTrip()
        val a = fixture.addPlant("Aloe", lastWatered = today.minusDays(12))
        val b = fixture.addPlant("Basil", lastWatered = today.minusDays(12))
        val visitDay = today.minusDays(5)
        val visit = fixture.addVisit(trip, visitDay, SitterVisitState.PLANNED, a to null, b to null)
        val vm = viewModel()
        val events = mutableListOf<PlanVisitEvent>()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events += it } }

        vm.open(visit.id)
        vm.toggleRow(b.id)
        vm.confirm().join()

        assertTrue(visitDay in waterLogDays(a))
        assertEquals(1, fixture.waterLogs(b.id).size)
        val stored = fixture.visits().single()
        assertEquals(SitterVisitState.CONFIRMED, stored.state)
        assertEquals(mapOf(a.id to true, b.id to false), stored.plants.associate { it.plantId to it.watered })
        assertEquals(listOf(PlanVisitEvent.Confirmed(logged = 1, skipped = 0)), events)
        assertNull(vm.uiState.value)
    }

    @Test
    fun `the logs have no post-watering reminder and every observation is excluded from learning`() = runTest {
        endedTrip()
        val a = fixture.addPlant("Aloe", lastWatered = today.minusDays(12))
        val visit = fixture.addVisit(trip, today.minusDays(5), SitterVisitState.PLANNED, a to null)
        val vm = viewModel()

        vm.open(visit.id)
        vm.confirm().join()

        assertTrue(fixture.postWateringReminders.isEmpty())
        assertEquals(1, fixture.adjustments.getByTrigger(a.id, WateringAdjustmentTrigger.AWAY_EXCLUDED).size)
    }

    @Test
    fun `a plant already watered on the visit day is skipped and reported`() = runTest {
        endedTrip()
        val visitDay = today.minusDays(5)
        val a = fixture.addPlant("Aloe", lastWatered = visitDay)
        val b = fixture.addPlant("Basil", lastWatered = today.minusDays(12))
        val visit = fixture.addVisit(trip, visitDay, SitterVisitState.PLANNED, a to null, b to null)
        val vm = viewModel()
        val events = mutableListOf<PlanVisitEvent>()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events += it } }

        vm.open(visit.id)
        vm.confirm().join()

        assertEquals(1, fixture.waterLogs(a.id).size)
        assertEquals(2, fixture.waterLogs(b.id).size)
        assertEquals(listOf(PlanVisitEvent.Confirmed(logged = 1, skipped = 1)), events)
    }

    @Test
    fun `the visit did not happen writes nothing and is stored as answered`() = runTest {
        endedTrip()
        val a = fixture.addPlant("Aloe", lastWatered = today.minusDays(12))
        val visit = fixture.addVisit(trip, today.minusDays(5), SitterVisitState.PLANNED, a to null)
        val vm = viewModel()
        val events = mutableListOf<PlanVisitEvent>()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events += it } }

        vm.open(visit.id)
        vm.didNotHappen().join()

        assertEquals(SitterVisitState.DID_NOT_HAPPEN, fixture.visits().single().state)
        assertEquals(1, fixture.waterLogs(a.id).size)
        assertEquals(listOf<PlanVisitEvent>(PlanVisitEvent.DidNotHappen), events)
        assertNull(vm.uiState.value)
    }

    @Test
    fun `an answered visit cannot be opened or written a second time`() = runTest {
        endedTrip()
        val a = fixture.addPlant("Aloe", lastWatered = today.minusDays(12))
        val visit = fixture.addVisit(trip, today.minusDays(5), SitterVisitState.PLANNED, a to null)
        val vm = viewModel()
        vm.open(visit.id)
        vm.confirm().join()

        vm.open(visit.id)
        vm.confirm().join()
        vm.didNotHappen().join()

        assertNull(vm.uiState.value)
        assertEquals(2, fixture.waterLogs(a.id).size)
        assertEquals(SitterVisitState.CONFIRMED, fixture.visits().single().state)
    }

    @Test
    fun `several visits are confirmed oldest first and each confirmed list is kept per visit`() = runTest {
        endedTrip()
        val a = fixture.addPlant("Aloe", lastWatered = today.minusDays(12))
        val b = fixture.addPlant("Basil", lastWatered = today.minusDays(12))
        val first = fixture.addVisit(trip, today.minusDays(8), SitterVisitState.PLANNED, a to null, b to null)
        val second = fixture.addVisit(trip, today.minusDays(3), SitterVisitState.PLANNED, a to null, b to null)
        val vm = viewModel()

        vm.open(first.id)
        vm.toggleRow(b.id)
        vm.confirm().join()
        vm.open(second.id)
        assertTrue(vm.uiState.value!!.rows.all { it.ticked })
        vm.confirm().join()

        val stored = fixture.visits().associateBy { it.id }
        assertEquals(false, stored.getValue(first.id).plants.first { it.plantId == b.id }.watered)
        assertEquals(true, stored.getValue(second.id).plants.first { it.plantId == b.id }.watered)
        assertEquals(listOf(today.minusDays(3)), waterLogDays(b).filter { it >= today.minusDays(8) })
    }

    @Test
    fun `a planned visit can be confirmed during the trip`() = runTest {
        trip = fixture.addTrip(today.minusDays(6), today.plusDays(6))
        val a = fixture.addPlant("Aloe", lastWatered = today.minusDays(12))
        val visitDay = today.minusDays(2)
        val visit = fixture.addVisit(trip, visitDay, SitterVisitState.PLANNED, a to null)
        val vm = viewModel()

        vm.open(visit.id)
        vm.confirm().join()

        assertTrue(visitDay in waterLogDays(a))
        assertEquals(SitterVisitState.CONFIRMED, fixture.visits().single().state)
        assertTrue(fixture.postWateringReminders.isEmpty())
    }

    @Test
    fun `closing the sheet leaves the visit planned and writes nothing`() = runTest {
        endedTrip()
        val a = fixture.addPlant("Aloe", lastWatered = today.minusDays(12))
        val visit = fixture.addVisit(trip, today.minusDays(5), SitterVisitState.PLANNED, a to null)
        val vm = viewModel()

        vm.open(visit.id)
        vm.close()

        assertNull(vm.uiState.value)
        assertEquals(SitterVisitState.PLANNED, fixture.visits().single().state)
        assertEquals(1, fixture.waterLogs(a.id).size)
    }
}
