package com.yapt.planttracker.ui.screens.calendar

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import app.cash.turbine.test
import com.yapt.planttracker.R
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.model.WateringFeedback
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
import java.util.TimeZone
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val application: Application = mockk {
        every { getString(R.string.quick_log_watered, any()) } answers { "Watered ${(args[1] as Array<*>)[0]}" }
        every { getString(R.string.quick_log_fertilized, any()) } answers { "Fertilized ${(args[1] as Array<*>)[0]}" }
        every { getString(R.string.quick_log_watered_and_fertilized, any()) } answers { "Watered and fertilized ${(args[1] as Array<*>)[0]}" }
        every { getString(R.string.quick_log_other, any(), any()) } answers { "${(args[1] as Array<*>)[0]} ${(args[1] as Array<*>)[1]}" }
    }
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(emptyPreferences())
    }
    private lateinit var vm: CalendarViewModel

    private fun plant(id: Long, name: String) = Plant(id = id, name = name, createdAt = 0L, updatedAt = 0L)

    @Before
    fun setup() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        PlantDetailViewModel.shownThisSession.clear()
        every { careLogRepo.logCount } returns flowOf(0)
        coEvery { careLogRepo.getLastLogOfType(any(), any()) } returns null
        coEvery { careLogRepo.getCareLogCount(any()) } returns 0
        coEvery { careLogRepo.getLastTwoWaterings(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        PlantDetailViewModel.shownThisSession.clear()
    }

    @Test
    fun `quickLog water emits watered snackbar and logs JUST_RIGHT feedback`() = runTest {
        val monstera = plant(1L, "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore)

        vm.quickLogEvent.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickLog(1L, CareType.WATER)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals("Watered Monstera", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.JUST_RIGHT })
        }
    }

    @Test
    fun `quickWaterWithFeedback TOO_LATE with different interval emits QuickWaterSuggestion`() = runTest {
        val now = System.currentTimeMillis()
        val fiveDaysAgo = now - TimeUnit.DAYS.toMillis(5)
        val tenDaysAgo = now - TimeUnit.DAYS.toMillis(10)
        val monstera = Plant(id = 1L, name = "Monstera", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT),
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = tenDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT)
        )
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore)

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
    fun `quickLog with photo reminder enabled and no recent photo emits PhotoReminderRequest`() = runTest {
        val monstera = plant(1L, "Monstera") // createdAt = 0L (far past)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { plantPhotoRepo.getPhotosForPlantOnce(any()) } returns emptyList()
        every { careLogRepo.getPhotoLogsForPlant(any()) } returns flowOf(emptyList())
        val enabledDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(preferencesOf(SettingsKeys.PHOTO_REMINDER_ENABLED to true))
        }
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, enabledDataStore)

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
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { plantPhotoRepo.getPhotosForPlantOnce(any()) } returns emptyList()
        every { careLogRepo.getPhotoLogsForPlant(any()) } returns flowOf(emptyList())
        val enabledDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(preferencesOf(SettingsKeys.PHOTO_REMINDER_ENABLED to true))
        }
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, enabledDataStore)

        vm.plantsWithStatus.test {
            awaitItem()
            vm.quickLog(1L, CareType.FERTILIZE)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.photoReminderRequest.value)
    }

    @Test
    fun `quickLog with photo reminder disabled does not emit PhotoReminderRequest`() = runTest {
        val monstera = plant(1L, "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        // default dataStore returns emptyPreferences -> PHOTO_REMINDER_ENABLED absent -> disabled
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore)

        vm.plantsWithStatus.test {
            awaitItem()
            vm.quickLog(1L, CareType.FERTILIZE)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.photoReminderRequest.value)
    }

    @Test
    fun `quickLiquidFertilizeWithFeedback JUST_RIGHT logs both care types, no interval suggestion`() = runTest {
        val monstera = Plant(id = 1L, name = "Monstera", useLiquidFertilizer = true, wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore)

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

        coVerify(exactly = 2) { careLogRepo.addLog(any()) }
        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.FERTILIZE && it.fertilizerType == FertilizerType.LIQUID })
        }
    }

    @Test
    fun `quickLiquidFertilizeWithFeedback TOO_LATE emits interval suggestion when interval differs`() = runTest {
        val now = System.currentTimeMillis()
        val fiveDaysAgo = now - TimeUnit.DAYS.toMillis(5)
        val tenDaysAgo = now - TimeUnit.DAYS.toMillis(10)
        val monstera = Plant(id = 1L, name = "Monstera", useLiquidFertilizer = true, wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT),
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = tenDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT)
        )
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore)

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
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore)

        val day = java.time.LocalDate.of(2026, 7, 15)
        vm.selectDay(day)
        assertEquals(day, vm.selectedDay.value)

        vm.selectDay(null)
        assertNull(vm.selectedDay.value)
    }

    @Test
    fun `setVisibleMonth updates plantsByDay window`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        vm = CalendarViewModel(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore)

        val month = java.time.YearMonth.of(2026, 9)
        vm.setVisibleMonth(month)
        advanceUntilIdle()

        assertEquals(month, vm.visibleMonth.value)
    }
}
