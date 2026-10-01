package com.yapt.planttracker.ui.screens.repotting

import app.cash.turbine.test
import com.yapt.planttracker.data.preferences.RepottingOverviewPreferences
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.repotting.RepottingOverviewThreshold
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.RepotPlanSeason
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import com.yapt.planttracker.util.toStartOfDayMillis
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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

@OptIn(ExperimentalCoroutinesApi::class)
class RepottingOverviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepository = mockk<PlantRepository>()
    private val careLogRepository = mockk<CareLogRepository>()
    private val preferences = mockk<RepottingOverviewPreferences>()
    private val quickLogUseCase = mockk<QuickLogUseCase>()

    private val plants = MutableStateFlow<List<Plant>>(emptyList())
    private val lastRepot = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val threshold = MutableStateFlow(RepottingOverviewThreshold.TWO_YEARS)
    private val today = LocalDate.of(2026, 9, 30)
    private val fixedNow = 1_700_000_000_000L

    @Before
    fun setUp() {
        every { plantRepository.getAllPlants() } returns plants
        every { careLogRepository.observeLastCareAtByPlant(CareType.REPOT) } returns lastRepot
        every { preferences.threshold } returns threshold
        coEvery { preferences.setThreshold(any()) } answers { threshold.value = firstArg() }
    }

    private fun millis(date: LocalDate) = date.toStartOfDayMillis()

    private fun plant(
        id: Long,
        name: String = "Plant $id",
        createdAt: LocalDate = LocalDate.of(2020, 1, 1),
        planStart: LocalDate? = null
    ) = Plant(
        id = id,
        name = name,
        createdAt = millis(createdAt),
        repotPlanSeasonStartAt = planStart?.let(::millis),
        repotPlanMadeAt = planStart?.let { millis(LocalDate.of(2026, 1, 1)) }
    )

    private fun buildVm(dayChange: Flow<LocalDate> = flowOf(today)) =
        RepottingOverviewViewModel(
            plantRepository = plantRepository,
            careLogRepository = careLogRepository,
            preferences = preferences,
            quickLogUseCase = quickLogUseCase,
            dayChange = dayChange,
            nowProvider = { fixedNow }
        )

    private fun TestScope.keepUiStateHot(vm: RepottingOverviewViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
    }

    private fun RepottingOverviewViewModel.ready() = uiState.value as RepottingOverviewUiState.Ready

    @Test
    fun `starts loading then reports the saved threshold and the overview`() = runTest {
        plants.value = listOf(plant(1, "Old"), plant(2, "Recent", createdAt = LocalDate.of(2026, 6, 1)))
        val vm = buildVm()

        vm.uiState.test {
            var state = awaitItem()
            if (state is RepottingOverviewUiState.Loading) state = awaitItem()
            state as RepottingOverviewUiState.Ready
            assertEquals(RepottingOverviewThreshold.TWO_YEARS, state.threshold)
            assertEquals(listOf(1L), state.overview.items.map { it.plant.id })
            assertTrue(state.hasActivePlants)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no plants reports no active plants`() = runTest {
        val vm = buildVm()
        keepUiStateHot(vm)

        assertFalse(vm.ready().hasActivePlants)
        assertTrue(vm.ready().overview.items.isEmpty())
    }

    @Test
    fun `planned plants are separate from the threshold list`() = runTest {
        plants.value = listOf(
            plant(1, "Planned", planStart = LocalDate.of(2026, 12, 1)),
            plant(2, "Plain")
        )
        val vm = buildVm()
        keepUiStateHot(vm)

        assertEquals(listOf(1L), vm.ready().overview.planned.map { it.plant.id })
        assertEquals(listOf(2L), vm.ready().overview.items.map { it.plant.id })
    }

    @Test
    fun `selecting a chip saves it and the state follows the saved value`() = runTest {
        plants.value = listOf(plant(1, createdAt = LocalDate.of(2025, 6, 1)))
        val vm = buildVm()
        keepUiStateHot(vm)
        assertTrue(vm.ready().overview.items.isEmpty())

        vm.selectThreshold(RepottingOverviewThreshold.NEVER)

        coVerify { preferences.setThreshold(RepottingOverviewThreshold.NEVER) }
        assertEquals(RepottingOverviewThreshold.NEVER, vm.ready().threshold)
        assertEquals(listOf(1L), vm.ready().overview.items.map { it.plant.id })
    }

    @Test
    fun `a repot logged elsewhere moves the plant out of the list in place`() = runTest {
        plants.value = listOf(plant(1))
        val vm = buildVm()
        keepUiStateHot(vm)
        assertEquals(listOf(1L), vm.ready().overview.items.map { it.plant.id })

        lastRepot.value = mapOf(1L to millis(today))

        assertTrue(vm.ready().overview.items.isEmpty())
    }

    @Test
    fun `a plan written elsewhere moves the plant into the planned group in place`() = runTest {
        plants.value = listOf(plant(1))
        val vm = buildVm()
        keepUiStateHot(vm)

        plants.value = listOf(plant(1, planStart = LocalDate.of(2026, 12, 1)))

        assertTrue(vm.ready().overview.items.isEmpty())
        assertEquals(listOf(1L), vm.ready().overview.planned.map { it.plant.id })
    }

    @Test
    fun `the list rolls over when the day changes at midnight`() = runTest {
        // Added 2024-10-01, so the 2+ year anniversary is 2026-10-01: not listed on the 30th, listed on the 1st.
        plants.value = listOf(plant(1, createdAt = LocalDate.of(2024, 10, 1)))
        val ticker = MutableSharedFlow<LocalDate>(replay = 1)
        ticker.tryEmit(LocalDate.of(2026, 9, 30))
        val vm = buildVm(dayChange = ticker)
        keepUiStateHot(vm)
        assertTrue(vm.ready().overview.items.isEmpty())

        ticker.emit(LocalDate.of(2026, 10, 1))

        assertEquals(listOf(1L), vm.ready().overview.items.map { it.plant.id })
    }

    @Test
    fun `an overdue plan stays planned and flips to overdue after its season ends`() = runTest {
        // Planned for the season starting 2026-06-01 (summer, northern); it ends 2026-09-01.
        plants.value = listOf(plant(1, planStart = LocalDate.of(2026, 6, 1)))
        val ticker = MutableSharedFlow<LocalDate>(replay = 1)
        ticker.tryEmit(LocalDate.of(2026, 8, 31))
        val vm = buildVm(dayChange = ticker)
        keepUiStateHot(vm)
        val before = vm.ready().overview.planned.single()

        ticker.emit(LocalDate.of(2026, 9, 1))
        val after = vm.ready().overview.planned.single()

        assertEquals(before.plant.id, after.plant.id)
        assertFalse(before.isOverdue)
        assertTrue(after.isOverdue)
    }

    @Test
    fun `repot logs a REPOT through quick log on the chosen date and reports its message`() = runTest {
        val fern = plant(1, "Fern")
        every { plantRepository.getPlantById(1L) } returns flowOf(fern)
        val loggedAt = millis(LocalDate.of(2026, 9, 1))
        coEvery { quickLogUseCase.quickLog(fern, CareType.REPOT, loggedAt) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Repotted Fern", logged = true)
        val vm = buildVm()

        vm.events.test {
            vm.repot(1L, loggedAt)
            assertEquals(RepottingOverviewViewModel.Event.Message("Repotted Fern"), awaitItem())
        }
        coVerify(exactly = 1) { quickLogUseCase.quickLog(fern, CareType.REPOT, loggedAt) }
    }

    @Test
    fun `planRepot writes only the plan columns with the season start and the clock`() = runTest {
        val fern = plant(1, "Fern")
        every { plantRepository.getPlantById(1L) } returns flowOf(fern)
        coEvery { plantRepository.setRepotPlan(any(), any(), any(), any()) } returns Unit
        val season = RepotPlanSeason(FertilizingSeason.SPRING, 2027, millis(LocalDate.of(2027, 3, 1)))
        val vm = buildVm()

        vm.events.test {
            vm.planRepot(1L, season)
            assertEquals(RepottingOverviewViewModel.Event.PlanSaved("Fern"), awaitItem())
        }
        coVerify(exactly = 1) { plantRepository.setRepotPlan(1L, season.startAtMillis, fixedNow, fixedNow) }
        coVerify(exactly = 0) { plantRepository.updatePlant(any()) }
    }

    @Test
    fun `clearPlan clears only the plan columns`() = runTest {
        val fern = plant(1, "Fern", planStart = LocalDate.of(2026, 12, 1))
        every { plantRepository.getPlantById(1L) } returns flowOf(fern)
        coEvery { plantRepository.clearRepotPlan(any(), any()) } returns Unit
        val vm = buildVm()

        vm.events.test {
            vm.clearPlan(1L)
            assertEquals(RepottingOverviewViewModel.Event.PlanCleared("Fern"), awaitItem())
        }
        coVerify(exactly = 1) { plantRepository.clearRepotPlan(1L, fixedNow) }
        coVerify(exactly = 0) { plantRepository.updatePlant(any()) }
    }

    @Test
    fun `an action on a plant that no longer exists does nothing`() = runTest {
        every { plantRepository.getPlantById(9L) } returns flowOf(null)
        val vm = buildVm()

        vm.events.test {
            vm.repot(9L, fixedNow)
            vm.planRepot(9L, RepotPlanSeason(FertilizingSeason.SPRING, 2027, millis(LocalDate.of(2027, 3, 1))))
            vm.clearPlan(9L)
            expectNoEvents()
        }
        coVerify(exactly = 0) { quickLogUseCase.quickLog(any(), any(), any()) }
        coVerify(exactly = 0) { plantRepository.setRepotPlan(any(), any(), any(), any()) }
        coVerify(exactly = 0) { plantRepository.clearRepotPlan(any(), any()) }
    }

    @Test
    fun `a failing write reports a failure instead of crashing`() = runTest {
        val fern = plant(1, "Fern")
        every { plantRepository.getPlantById(1L) } returns flowOf(fern)
        coEvery { quickLogUseCase.quickLog(any(), any(), any()) } throws IllegalStateException("boom")
        val vm = buildVm()

        vm.events.test {
            vm.repot(1L, fixedNow)
            assertEquals(RepottingOverviewViewModel.Event.ActionFailed, awaitItem())
        }
    }

    @Test
    fun `writes from this page run one at a time so a plan cannot be overwritten mid-repot`() = runTest {
        val fern = plant(1, "Fern")
        every { plantRepository.getPlantById(1L) } returns flowOf(fern)
        val repotGate = CompletableDeferred<Unit>()
        coEvery { quickLogUseCase.quickLog(fern, CareType.REPOT, any()) } coAnswers {
            repotGate.await()
            QuickLogUseCase.QuickLogOutcome(message = "Repotted Fern", logged = true)
        }
        coEvery { plantRepository.setRepotPlan(any(), any(), any(), any()) } returns Unit
        val season = RepotPlanSeason(FertilizingSeason.SPRING, 2027, millis(LocalDate.of(2027, 3, 1)))
        val vm = buildVm()

        vm.repot(1L, fixedNow)
        vm.planRepot(1L, season)

        coVerify(exactly = 0) { plantRepository.setRepotPlan(any(), any(), any(), any()) }
        repotGate.complete(Unit)
        coVerify(exactly = 1) { plantRepository.setRepotPlan(1L, season.startAtMillis, fixedNow, fixedNow) }
    }
}
