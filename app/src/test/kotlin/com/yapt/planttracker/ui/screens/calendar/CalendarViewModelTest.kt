package com.yapt.planttracker.ui.screens.calendar

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PhotoReminderRequest
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val application: Application = mockk {
        every { getString(R.string.quick_log_watered, any()) } answers { "Watered ${(args[1] as Array<*>)[0]}" }
        every { getString(R.string.quick_log_watered_and_fertilized, any()) } answers { "Watered and fertilized ${(args[1] as Array<*>)[0]}" }
    }
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(emptyPreferences())
    }
    private val quickLogUseCase: QuickLogUseCase = mockk()
    private lateinit var vm: CalendarViewModel

    private fun plant(id: Long, name: String) = Plant(id = id, name = name, createdAt = 0L, updatedAt = 0L)

    @Before
    fun setup() {
        PlantDetailViewModel.shownThisSession.clear()
        every { careLogRepo.logCount } returns flowOf(0)
        coEvery { careLogRepo.getLastLogOfType(any(), any()) } returns null
        coEvery { careLogRepo.getCareLogCount(any()) } returns 0
        // Default: no photo reminder unless a test explicitly stubs otherwise.
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(any()) } returns null
    }

    @After
    fun tearDown() {
        PlantDetailViewModel.shownThisSession.clear()
    }

    // quickLog/quickWaterWithFeedback/quickLiquidFertilizeWithFeedback delegate the actual
    // care-log persistence, override clearing, and adaptive-interval computation to
    // QuickLogUseCase (see QuickLogUseCaseTest). These tests verify VM-level orchestration only:
    // the right use-case method is invoked with the resolved plant, and its result is mapped onto
    // the correct StateFlow/SharedFlow.

    @Test
    fun `quickLog water routes through quickWaterWithFeedback and emits its snackbar message`() = runTest {
        val monstera = plant(1L, "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT) } returns null
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase)

        vm.quickLogEvent.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickLog(1L, CareType.WATER)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals("Watered Monstera", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT) }
    }

    @Test
    fun `quickWaterWithFeedback emits the QuickWaterSuggestion returned by the use case`() = runTest {
        val monstera = Plant(id = 1L, name = "Monstera", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickWaterWithFeedback(monstera, WateringFeedback.TOO_LATE) } returns
            QuickWaterSuggestion(plantId = 1L, plantName = "Monstera", suggestedInterval = 4)
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase)

        vm.quickWaterSuggestion.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickWaterWithFeedback(1L, WateringFeedback.TOO_LATE)
                cancelAndIgnoreRemainingEvents()
            }
            val suggestion = awaitItem()
            assertEquals(1L, suggestion.plantId)
            assertEquals(4, suggestion.suggestedInterval)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `quickLog emits a PhotoReminderRequest when the use case returns one`() = runTest {
        val monstera = plant(1L, "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE) } returns "Fertilized Monstera"
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns
            PhotoReminderRequest(plantId = 1L, plantName = "Monstera", daysSince = 45L)
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase)

        vm.plantsWithStatus.test {
            awaitItem()
            vm.quickLog(1L, CareType.FERTILIZE)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        val request = vm.photoReminderRequest.value
        assertNotNull(request)
        assertEquals(1L, request!!.plantId)
    }

    @Test
    fun `quickLog does not emit photo reminder when plant already reminded this session`() = runTest {
        // Simulates the plant having already been reminded on Plants tab or Plant Detail this session.
        PlantDetailViewModel.shownThisSession.add(1L)
        val monstera = plant(1L, "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE) } returns "Fertilized Monstera"
        // Default @Before stub already returns null for maybeBuildPhotoReminderRequest; this test
        // documents that the gating (session suppression) lives in QuickLogUseCase, not the VM.
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase)

        vm.plantsWithStatus.test {
            awaitItem()
            vm.quickLog(1L, CareType.FERTILIZE)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.photoReminderRequest.value)
    }

    @Test
    fun `quickLog does not emit photo reminder when the use case returns null`() = runTest {
        val monstera = plant(1L, "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE) } returns "Fertilized Monstera"
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase)

        vm.plantsWithStatus.test {
            awaitItem()
            vm.quickLog(1L, CareType.FERTILIZE)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.photoReminderRequest.value)
    }

    @Test
    fun `quickLiquidFertilizeWithFeedback emits watered-and-fertilized message, no interval suggestion`() = runTest {
        val monstera = Plant(id = 1L, name = "Monstera", useLiquidFertilizer = true, wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickLiquidFertilizeWithFeedback(monstera, WateringFeedback.JUST_RIGHT) } returns null
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase)

        vm.quickWaterSuggestion.test {
            vm.quickLogEvent.test {
                vm.plantsWithStatus.test {
                    awaitItem()
                    vm.quickLiquidFertilizeWithFeedback(1L, WateringFeedback.JUST_RIGHT)
                    cancelAndIgnoreRemainingEvents()
                }
                assertEquals("Watered and fertilized Monstera", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.quickLiquidFertilizeWithFeedback(monstera, WateringFeedback.JUST_RIGHT) }
    }

    @Test
    fun `quickLiquidFertilizeWithFeedback emits the suggestion returned by the use case`() = runTest {
        val monstera = Plant(id = 1L, name = "Monstera", useLiquidFertilizer = true, wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickLiquidFertilizeWithFeedback(monstera, WateringFeedback.TOO_LATE) } returns
            QuickWaterSuggestion(plantId = 1L, plantName = "Monstera", suggestedInterval = 4)
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase)

        vm.quickWaterSuggestion.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickLiquidFertilizeWithFeedback(1L, WateringFeedback.TOO_LATE)
                cancelAndIgnoreRemainingEvents()
            }
            val suggestion = awaitItem()
            assertEquals(1L, suggestion.plantId)
            assertEquals(4, suggestion.suggestedInterval)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selectDay updates selectedDay and selectDay null clears it`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase)

        val day = java.time.LocalDate.of(2026, 7, 15)
        vm.selectDay(day)
        assertEquals(day, vm.selectedDay.value)

        vm.selectDay(null)
        assertNull(vm.selectedDay.value)
    }

    @Test
    fun `setVisibleMonth updates plantsByDay window`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase)

        val month = java.time.YearMonth.of(2026, 9)
        vm.setVisibleMonth(month)
        advanceUntilIdle()

        assertEquals(month, vm.visibleMonth.value)
    }
}
