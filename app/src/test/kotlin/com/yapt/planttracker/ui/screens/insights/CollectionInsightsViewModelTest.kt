package com.yapt.planttracker.ui.screens.insights

import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.util.MainDispatcherRule
import com.yapt.planttracker.util.toStartOfDayMillis
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
class CollectionInsightsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepository = mockk<PlantRepository>()
    private val careLogRepository = mockk<CareLogRepository>()
    private val issueRepository = mockk<PlantIssueRepository>()
    private val awayRepository = mockk<AwayPeriodRepository>()
    private val plants = MutableStateFlow<List<Plant>>(emptyList())
    private val logs = MutableStateFlow<List<CareLog>>(emptyList())
    private val issues = MutableStateFlow<List<PlantIssue>>(emptyList())
    private val away = MutableStateFlow<List<AwayPeriod>>(emptyList())
    private val ticker = MutableSharedFlow<LocalDate>(replay = 1)
    private val day = LocalDate.of(2026, 7, 15)
    private var nowDay = day

    @Before
    fun setUp() {
        every { plantRepository.getAllPlants() } returns plants
        every { careLogRepository.getAllLogs() } returns logs
        every { issueRepository.getAllIssues() } returns issues
        every { awayRepository.observeAll() } returns away
        ticker.tryEmit(day)
    }

    private fun noon(date: LocalDate) = date.toStartOfDayMillis() + TimeUnit.HOURS.toMillis(12)

    private fun buildVm() = CollectionInsightsViewModel(
        plantRepository = plantRepository,
        careLogRepository = careLogRepository,
        plantIssueRepository = issueRepository,
        awayPeriodRepository = awayRepository,
        seasonalAmplitude = MutableStateFlow(0.0),
        dayChange = ticker,
        nowProvider = { noon(nowDay) },
        hemisphere = { Hemisphere.NORTHERN }
    )

    private fun TestScope.keepHot(vm: CollectionInsightsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
    }

    private fun CollectionInsightsViewModel.ready() = uiState.value as CollectionInsightsUiState.Ready

    private fun water(date: LocalDate) =
        CareLog(plantId = 1, careType = CareType.WATER, loggedAt = noon(date))

    @Test
    fun `no plants reports no active plants`() = runTest {
        val vm = buildVm()
        keepHot(vm)

        assertFalse(vm.ready().hasActivePlants)
    }

    @Test
    fun `reports late waterings, care counts and active issues`() = runTest {
        plants.value = listOf(Plant(id = 1, name = "Fern", wateringIntervalDays = 7, pinIntervalToBase = true))
        logs.value = listOf(water(day.minusDays(25)), water(day.minusDays(15)), water(day.minusDays(5)))
        issues.value = listOf(PlantIssue(id = 1, plantId = 1, name = "Mites", startedAt = noon(day.minusDays(4))))
        val vm = buildVm()
        keepHot(vm)

        val insights = vm.ready().insights
        assertTrue(vm.ready().hasActivePlants)
        assertEquals(2, insights.lateWaterings.single().lateCount)
        assertEquals(3, insights.totalCareCount)
        assertEquals(4, insights.activeIssues.single().daysActive)
    }

    @Test
    fun `the window rolls when the day changes`() = runTest {
        plants.value = listOf(Plant(id = 1, name = "Fern", wateringIntervalDays = 7, pinIntervalToBase = true))
        logs.value = listOf(water(day.minusDays(25)), water(day.minusDays(15)), water(day.minusDays(5)))
        val vm = buildVm()
        keepHot(vm)
        assertEquals(1, vm.ready().insights.lateWaterings.size)

        nowDay = day.plusDays(25)
        ticker.emit(nowDay)

        assertTrue(vm.ready().insights.lateWaterings.isEmpty())
    }

    @Test
    fun `a new log updates the page`() = runTest {
        plants.value = listOf(Plant(id = 1, name = "Fern", wateringIntervalDays = 7, pinIntervalToBase = true))
        val vm = buildVm()
        keepHot(vm)
        assertEquals(0, vm.ready().insights.totalCareCount)

        logs.value = listOf(water(day))

        assertEquals(1, vm.ready().insights.totalCareCount)
    }
}
