package com.yapt.planttracker.ui.screens.calendar

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import app.cash.turbine.test
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.PhotoReminderRequest
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.model.WateringReason
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
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
        every {
            getString(R.string.quick_log_watered_and_fertilized, any())
        } answers { "Watered and fertilized ${(args[1] as Array<*>)[0]}" }
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
        PhotoReminderPolicy.shownThisSession.clear()
        every { careLogRepo.logCount } returns flowOf(0)
        coEvery { careLogRepo.getLastLogOfType(any(), any()) } returns null
        coEvery { careLogRepo.getCareLogCount(any()) } returns 0
        // Default: no photo reminder unless a test explicitly stubs otherwise.
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(any()) } returns null
    }

    @After
    fun tearDown() {
        PhotoReminderPolicy.shownThisSession.clear()
    }

    // quickLog/quickWater/quickLiquidFertilize delegate the actual
    // care-log persistence, override clearing, and adaptive-interval computation to
    // QuickLogUseCase (see QuickLogUseCaseTest). These tests verify VM-level orchestration only:
    // the right use-case method is invoked with the resolved plant, and its result is mapped onto
    // the correct StateFlow/SharedFlow.

    @Test
    fun `quickLog water routes through quickWater and emits its snackbar message`() = runTest {
        val monstera = plant(1L, "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickWaterWithReason(monstera, null) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Watered Monstera", logged = true)
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

        coVerify { quickLogUseCase.quickWaterWithReason(monstera, null) }
    }

    @Test
    fun `quickWater emits the QuickWaterSuggestion returned by the use case`() = runTest {
        val monstera = Plant(id = 1L, name = "Monstera", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT) } returns
            QuickLogUseCase.QuickLogOutcome(
                message = "Watered Monstera",
                logged = true,
                suggestion = QuickWaterSuggestion(
                    plantId = 1L,
                    plantName = "Monstera",
                    suggestedInterval = 4,
                    suggestedIntervalEffective = 5
                )
            )
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase)

        vm.quickWaterSuggestion.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickWater(1L, WateringReason.PLANT_NEEDED_IT)
                cancelAndIgnoreRemainingEvents()
            }
            val suggestion = awaitItem()
            assertEquals(1L, suggestion.plantId)
            assertEquals(4, suggestion.suggestedInterval)
            // #620: the dialog binds display/gating to suggestedIntervalEffective, not the raw
            // base-space suggestedInterval — verify CalendarViewModel forwards it unchanged, since it
            // does no conversion of its own (QuickLogUseCase.computeSuggestion() is the sole source).
            assertEquals(5, suggestion.suggestedIntervalEffective)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `quickLog emits a PhotoReminderRequest when the use case returns one`() = runTest {
        val monstera = plant(1L, "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Fertilized Monstera", logged = true)
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
        PhotoReminderPolicy.shownThisSession.add(1L)
        val monstera = plant(1L, "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Fertilized Monstera", logged = true)
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
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Fertilized Monstera", logged = true)
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
    fun `quickLiquidFertilize emits watered-and-fertilized message, no interval suggestion`() = runTest {
        val monstera = Plant(
            id = 1L,
            name = "Monstera",
            useLiquidFertilizer = true,
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickLiquidFertilizeWithReason(monstera, null) } returns
            QuickLogUseCase.QuickLogOutcome(
                message = "Watered and fertilized Monstera",
                logged = true,
                waterPaired = true
            )
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase)

        vm.quickWaterSuggestion.test {
            vm.quickLogEvent.test {
                vm.plantsWithStatus.test {
                    awaitItem()
                    vm.quickLiquidFertilize(1L, null)
                    cancelAndIgnoreRemainingEvents()
                }
                assertEquals("Watered and fertilized Monstera", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.quickLiquidFertilizeWithReason(monstera, null) }
    }

    @Test
    fun `quickLiquidFertilize emits the suggestion returned by the use case`() = runTest {
        val monstera = Plant(
            id = 1L,
            name = "Monstera",
            useLiquidFertilizer = true,
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickLiquidFertilizeWithReason(monstera, WateringReason.PLANT_NEEDED_IT) } returns
            QuickLogUseCase.QuickLogOutcome(
                message = "Watered and fertilized Monstera",
                logged = true,
                waterPaired = true,
                suggestion = QuickWaterSuggestion(
                    plantId = 1L,
                    plantName = "Monstera",
                    suggestedInterval = 4,
                    suggestedIntervalEffective = 4
                )
            )
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase)

        vm.quickWaterSuggestion.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickLiquidFertilize(1L, WateringReason.PLANT_NEEDED_IT)
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

    // applySuggestedInterval / dismissSuggestedInterval mirror PlantDetailViewModel's equivalents
    // (#568 comment 5) so the ADR-0006 dialog has the same confidence effect regardless of which
    // of the three screens it was shown from. The confidence math itself is covered by
    // CareScheduleAdaptiveTest; these verify the VM wires the flag check and repo update.

    @Test
    fun `applySuggestedInterval persists the new interval and adaptive confidence when flag enabled`() = runTest {
        val enabledDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true)
            )
        }
        val monstera = plant(1L, "Monstera").copy(wateringConfidence = 2)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { plantRepo.updatePlant(any()) } just runs
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, enabledDataStore, quickLogUseCase)

        // Applied value == suggested value: within GAP_AGREEMENT_TOLERANCE, so confidenceAfterDialogEdit
        // leaves confidence unchanged at 2 (normal rules), not a reset.
        vm.applySuggestedInterval(1L, suggestedIntervalDays = 10, newInterval = 10)
        advanceUntilIdle()

        coVerify { plantRepo.updatePlant(match { it.wateringIntervalDays == 10 && it.wateringConfidence == 2 }) }
    }

    @Test
    fun `dismissSuggestedInterval raises confidence when flag enabled`() = runTest {
        val enabledDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true)
            )
        }
        val monstera = plant(1L, "Monstera").copy(wateringConfidence = 1)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { plantRepo.updatePlant(any()) } just runs
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, enabledDataStore, quickLogUseCase)

        vm.dismissSuggestedInterval(1L)
        advanceUntilIdle()

        coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 2 }) }
    }

    @Test
    fun `dismissSuggestedInterval does nothing when flag disabled`() = runTest {
        val monstera = plant(1L, "Monstera").copy(wateringConfidence = 1)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase)

        vm.dismissSuggestedInterval(1L)
        advanceUntilIdle()

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }
}
