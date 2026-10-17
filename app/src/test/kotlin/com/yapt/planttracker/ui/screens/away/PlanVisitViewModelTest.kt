package com.yapt.planttracker.ui.screens.away

import com.yapt.planttracker.domain.away.SitterList
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.SitterVisitState
import com.yapt.planttracker.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Plan a sitter visit (#899, product ADR-0065): the confirm step, the plan state and sharing. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlanVisitViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class RecordingOutput : SitterListOutput {
        val pdfLists = mutableListOf<SitterList>()
        val textLists = mutableListOf<SitterList>()
        val file: File = File.createTempFile("sitter", ".pdf")

        override fun writePdf(list: SitterList): File {
            pdfLists += list
            return file
        }

        override fun text(list: SitterList): String {
            textLists += list
            return "text for ${list.visitDay}"
        }

        override fun caption(list: SitterList) = "caption for ${list.visitDay}"
    }

    private lateinit var fixture: SitterFlowFixture
    private lateinit var output: RecordingOutput
    private lateinit var today: LocalDate
    private lateinit var trip: AwayPeriod

    @Before
    fun setUp() {
        fixture = SitterFlowFixture()
        output = RecordingOutput()
        today = fixture.today
    }

    @After
    fun tearDown() {
        fixture.close()
        output.file.delete()
    }

    private suspend fun startTrip(visitEveryDays: Int? = 7) {
        trip = fixture.addTrip(today.minusDays(6), today.plusDays(10), visitEveryDays)
    }

    private fun TestScope.viewModel(): PlanVisitViewModel {
        val vm = PlanVisitViewModel(
            PlanVisitDependencies(
                fixture.awayRepo,
                fixture.plantRepo,
                fixture.careLogRepo,
                fixture.useCase,
                flowOf(0.0),
                output
            ),
            dayChange = flowOf(today),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
        return vm
    }

    private fun PlanVisitViewModel.plan() = uiState.value as PlanVisitUiState.Plan
    private fun PlanVisitViewModel.confirm() = uiState.value as PlanVisitUiState.Confirm

    private suspend fun waterLogDays(plant: Plant) = fixture.waterLogs(plant.id).map {
        Instant.ofEpochMilli(it.loggedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    @Test
    fun `without a trip there is nothing to plan`() = runTest {
        assertEquals(PlanVisitUiState.NoTrip, viewModel().uiState.value)
    }

    @Test
    fun `a trip that has not started defaults to its first day and not to today`() = runTest {
        trip = fixture.addTrip(today.plusDays(3), today.plusDays(9))

        val plan = viewModel().plan()

        assertEquals(today.plusDays(3), plan.visitDay)
        assertFalse(plan.todayAllowed)
        assertFalse(plan.tomorrowAllowed)
    }

    @Test
    fun `a visit can only be planned inside the trip and not in the past`() = runTest {
        startTrip()
        val vm = viewModel()
        assertEquals(today, vm.plan().visitDay)

        vm.pickDay(today.minusDays(2))
        assertEquals(today, vm.plan().visitDay)
        vm.pickDay(today.plusDays(11))
        assertEquals(today, vm.plan().visitDay)
        vm.pickDay(today.plusDays(4))
        assertEquals(today.plusDays(4), vm.plan().visitDay)
        assertTrue(vm.plan().todayAllowed)
        assertTrue(vm.plan().tomorrowAllowed)
    }

    @Test
    fun `tomorrow is not offered on the last day of the trip`() = runTest {
        trip = fixture.addTrip(today.minusDays(2), today)

        assertFalse(viewModel().plan().tomorrowAllowed)
    }

    @Test
    fun `the gap defaults to the trips rhythm, else seven, and stays between one and fourteen`() = runTest {
        startTrip(visitEveryDays = 10)
        val vm = viewModel()
        assertEquals(10, vm.plan().gapDays)

        vm.setGapDays(0)
        assertEquals(1, vm.plan().gapDays)
        vm.setGapDays(99)
        assertEquals(14, vm.plan().gapDays)
    }

    @Test
    fun `an unset rhythm defaults the gap to seven`() = runTest {
        startTrip(visitEveryDays = null)

        assertEquals(7, viewModel().plan().gapDays)
    }

    @Test
    fun `the plan lists what is due by the visit and counts its rooms`() = runTest {
        startTrip()
        fixture.addPlant("Due", interval = 5, lastWatered = today.minusDays(5), room = "Kitchen", photo = "content://p")
        fixture.addPlant("Later", interval = 30, lastWatered = today)

        val plan = viewModel().plan()

        assertEquals(listOf("Due"), plan.projection.listed.map { it.plant.name })
        assertEquals(1, plan.summary.plantCount)
        assertEquals("Kitchen", plan.summary.roomCounts.single().room)
    }

    @Test
    fun `a planned visit before today opens the checklist with every plant ticked`() = runTest {
        startTrip()
        val a = fixture.addPlant("Aloe", lastWatered = today.minusDays(10))
        val b = fixture.addPlant("Basil", lastWatered = today.minusDays(10))
        fixture.addVisit(trip, today.minusDays(2), SitterVisitState.PLANNED, a to null, b to null)

        val confirm = viewModel().confirm()

        assertEquals(listOf("Aloe", "Basil"), confirm.rows.map { it.plant.name })
        assertTrue(confirm.rows.all { it.ticked })
    }

    @Test
    fun `continue logs exactly the ticked plants on the visit day and stores the visit as confirmed`() = runTest {
        startTrip()
        val a = fixture.addPlant("Aloe", lastWatered = today.minusDays(10))
        val b = fixture.addPlant("Basil", lastWatered = today.minusDays(10))
        val visitDay = today.minusDays(2)
        fixture.addVisit(trip, visitDay, SitterVisitState.PLANNED, a to null, b to null)
        val vm = viewModel()
        val events = mutableListOf<PlanVisitEvent>()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events += it } }

        vm.toggleConfirmRow(b.id)
        vm.confirmVisit().join()

        assertTrue(visitDay in waterLogDays(a))
        assertEquals(1, fixture.waterLogs(b.id).size)
        assertFalse(visitDay in waterLogDays(b))
        val visit = fixture.visits().single()
        assertEquals(SitterVisitState.CONFIRMED, visit.state)
        assertEquals(mapOf(a.id to true, b.id to false), visit.plants.associate { it.plantId to it.watered })
        assertEquals(listOf(PlanVisitEvent.Confirmed(logged = 1, skipped = 0)), events)
        assertTrue(vm.uiState.value is PlanVisitUiState.Plan)
    }

    @Test
    fun `the confirm logs never schedule the post-watering reminder`() = runTest {
        startTrip()
        val a = fixture.addPlant("Aloe", lastWatered = today.minusDays(10))
        fixture.addVisit(trip, today.minusDays(2), SitterVisitState.PLANNED, a to null)

        viewModel().confirmVisit().join()

        assertTrue(fixture.postWateringReminders.isEmpty())
    }

    @Test
    fun `the visit did not happen stores that state and writes nothing`() = runTest {
        startTrip()
        val a = fixture.addPlant("Aloe", lastWatered = today.minusDays(10))
        fixture.addVisit(trip, today.minusDays(2), SitterVisitState.PLANNED, a to null)
        val vm = viewModel()

        vm.visitDidNotHappen().join()

        assertEquals(SitterVisitState.DID_NOT_HAPPEN, fixture.visits().single().state)
        assertEquals(1, fixture.waterLogs(a.id).size)
        assertTrue(vm.uiState.value is PlanVisitUiState.Plan)
    }

    @Test
    fun `going back leaves the visit planned, writes nothing and plans as if it was watered`() = runTest {
        startTrip()
        val a = fixture.addPlant("Aloe", interval = 10, lastWatered = today.minusDays(12))
        fixture.addVisit(trip, today.minusDays(2), SitterVisitState.PLANNED, a to null)
        val vm = viewModel()
        assertTrue(vm.uiState.value is PlanVisitUiState.Confirm)

        vm.skipConfirm()

        assertEquals(SitterVisitState.PLANNED, fixture.visits().single().state)
        assertEquals(1, fixture.waterLogs(a.id).size)
        // Watered two days ago on a 10-day interval, so it is not due: the whole list counted as watered.
        assertTrue(vm.plan().projection.listed.isEmpty())
    }

    @Test
    fun `a confirmed visit tags the skipped plant on the next list`() = runTest {
        startTrip()
        val a = fixture.addPlant("Aloe", interval = 10, lastWatered = today.minusDays(12))
        val b = fixture.addPlant("Basil", interval = 10, lastWatered = today.minusDays(12))
        fixture.addVisit(trip, today.minusDays(2), SitterVisitState.PLANNED, a to null, b to null)
        val vm = viewModel()

        vm.toggleConfirmRow(b.id)
        vm.confirmVisit().join()

        val listed = vm.plan().projection.listed
        assertEquals(listOf("Basil"), listed.map { it.plant.name })
        assertTrue(listed.single().missedLastTime)
    }

    @Test
    fun `a visit planned for the future is not offered for confirmation`() = runTest {
        startTrip()
        val a = fixture.addPlant("Aloe")
        fixture.addVisit(trip, today.plusDays(2), SitterVisitState.PLANNED, a to null)

        assertTrue(viewModel().uiState.value is PlanVisitUiState.Plan)
    }

    @Test
    fun `a visit planned for today stays on the plan screen after it is shared`() = runTest {
        startTrip()
        fixture.addPlant("Due", interval = 5, lastWatered = today.minusDays(5))
        val vm = viewModel()

        vm.share(asText = true).join()

        assertTrue(vm.uiState.value is PlanVisitUiState.Plan)
    }

    @Test
    fun `sharing the pdf saves a planned visit with its plants and hands over the file`() = runTest {
        startTrip()
        val due = fixture.addPlant("Due", interval = 5, lastWatered = today.minusDays(5), photo = "content://p")
        fixture.addPlant("Later", interval = 30, lastWatered = today)
        val vm = viewModel()
        val events = mutableListOf<PlanVisitEvent>()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events += it } }

        vm.share(asText = false).join()

        val visit = fixture.visits().single()
        assertEquals(today, visit.day)
        assertEquals(SitterVisitState.PLANNED, visit.state)
        assertEquals(listOf(due.id), visit.plants.map { it.plantId })
        assertNull(visit.plants.single().watered)
        assertEquals(listOf(PlanVisitEvent.SharePdf(output.file, "caption for $today")), events)
        assertEquals(1, output.pdfLists.size)
    }

    @Test
    fun `sharing as text saves the visit too and emits the text`() = runTest {
        startTrip()
        fixture.addPlant("Due", interval = 5, lastWatered = today.minusDays(5))
        val vm = viewModel()
        val events = mutableListOf<PlanVisitEvent>()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events += it } }

        vm.share(asText = true).join()

        assertEquals(1, fixture.visits().size)
        assertEquals(listOf(PlanVisitEvent.ShareText("text for $today")), events)
        assertTrue(output.pdfLists.isEmpty())
    }

    @Test
    fun `re-planning the same day replaces that visits list`() = runTest {
        startTrip()
        val due = fixture.addPlant("Due", interval = 5, lastWatered = today.minusDays(5))
        val soon = fixture.addPlant("Soon", interval = 8, lastWatered = today.minusDays(3))
        val vm = viewModel()
        vm.setGapDays(1)
        vm.share(asText = true).join()
        assertEquals(listOf(due.id), fixture.visits().single().plants.map { it.plantId })

        vm.setGapDays(14)
        vm.share(asText = true).join()

        val visit = fixture.visits().single()
        assertEquals(setOf(due.id, soon.id), visit.plants.map { it.plantId }.toSet())
    }

    @Test
    fun `an empty list is not shared or saved`() = runTest {
        startTrip()
        fixture.addPlant("Fine", interval = 30, lastWatered = today)
        val vm = viewModel()

        vm.share(asText = false).join()

        assertTrue(fixture.visits().isEmpty())
        assertTrue(output.pdfLists.isEmpty())
    }

    @Test
    fun `planning writes no plant row`() = runTest {
        startTrip()
        val due = fixture.addPlant("Due", interval = 5, lastWatered = today.minusDays(5))
        val before = fixture.plantRepo.getAllPlants().first()
        val vm = viewModel()

        vm.share(asText = true).join()

        assertEquals(before, fixture.plantRepo.getAllPlants().first())
        assertEquals(1, fixture.waterLogs(due.id).size)
    }
}
