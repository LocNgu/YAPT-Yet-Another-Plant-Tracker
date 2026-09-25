package com.yapt.planttracker.ui.screens.plantdetail

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * #531 review round 1 (product ADR-0048/product ADR-0050): the stale-snapshot race (a burst of −/+
 * taps writing from the same cached `plant.value`) and the tap-burst coalescing window. Each test
 * shares [mainDispatcherRule]'s single [kotlinx.coroutines.test.TestDispatcher] between `runTest`,
 * `viewModelScope` (via [MainDispatcherRule] swapping `Dispatchers.Main`), and the ViewModel's
 * `applicationScope`, so `advanceUntilIdle()` deterministically drives the real
 * `INTERVAL_TAP_COALESCE_WINDOW_MS` delay without an actual 1-second wait.
 */
class PlantDetailScheduleSettingsActionsCoalescingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(emptyPreferences())
    }
    private val quickLogUseCase: QuickLogUseCase = mockk()
    private val customReminderRepo: CustomReminderRepository = mockk()
    private val plantIssueRepo: PlantIssueRepository = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk {
        every { getRecentForPlant(any(), any()) } returns flowOf(emptyList())
        coEvery { addAdjustment(any()) } returns 1L
    }
    private val database: PlantDatabase = mockk()

    // pinIntervalToBase = true so the written/logged numbers are the literal tap values, not run
    // through the seasonal curve — that math is unrelated to what this test class verifies.
    private fun plant(wateringIntervalDays: Int? = null, fertilizingIntervalDays: Int? = null) = Plant(
        id = 1L,
        name = "Monstera",
        createdAt = 0L,
        updatedAt = 0L,
        wateringIntervalDays = wateringIntervalDays,
        fertilizingIntervalDays = fertilizingIntervalDays,
        pinIntervalToBase = true
    )

    private fun makeVm(stored: MutableStateFlow<Plant?>): PlantDetailViewModel {
        every { careLogRepo.getLogsForPlant(1L) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(1L) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(1L) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(1L) } returns flowOf(emptyList())
        every { plantRepo.getPlantById(1L) } returns stored
        coEvery { plantRepo.updatePlant(any()) } coAnswers { stored.value = firstArg() }
        return PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            1L,
            dataStore,
            quickLogUseCase,
            customReminderRepo,
            plantIssueRepo,
            database,
            wateringAdjustmentRepo,
            CoroutineScope(mainDispatcherRule.testDispatcher)
        )
    }

    @Test
    fun `a burst of taps produces one write and one MANUAL_EDIT row`() = runTest(mainDispatcherRule.testDispatcher) {
        val stored = MutableStateFlow<Plant?>(plant(wateringIntervalDays = 7))
        val vm = makeVm(stored)

        vm.setWateringInterval(8, viaButtonTap = true)
        vm.setWateringInterval(9, viaButtonTap = true)
        vm.setWateringInterval(10, viaButtonTap = true)
        vm.setWateringInterval(11, viaButtonTap = true)
        vm.setWateringInterval(12, viaButtonTap = true)
        advanceUntilIdle()

        assertEquals(12, stored.value?.wateringIntervalDays)
        coVerify(exactly = 1) { plantRepo.updatePlant(match { it.wateringIntervalDays == 12 }) }
        for (intermediate in 8..11) {
            coVerify(exactly = 0) { plantRepo.updatePlant(match { it.wateringIntervalDays == intermediate }) }
        }
        coVerify(exactly = 1) {
            wateringAdjustmentRepo.addAdjustment(
                match { it.beforeIntervalDays == 7 && it.afterIntervalDays == 12 }
            )
        }
    }

    @Test
    fun `a release after pending taps wins and cancels the pending tap`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stored = MutableStateFlow<Plant?>(plant(wateringIntervalDays = 7))
            val vm = makeVm(stored)

            vm.setWateringInterval(8, viaButtonTap = true)
            vm.setWateringInterval(15, viaButtonTap = false)
            advanceUntilIdle()

            assertEquals(15, stored.value?.wateringIntervalDays)
            coVerify(exactly = 0) { plantRepo.updatePlant(match { it.wateringIntervalDays == 8 }) }
            coVerify(exactly = 1) { plantRepo.updatePlant(match { it.wateringIntervalDays == 15 }) }
        }

    @Test
    fun `a switch off after a pending tap wins and cancels the pending tap`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stored = MutableStateFlow<Plant?>(plant(wateringIntervalDays = 7))
            val vm = makeVm(stored)

            vm.setWateringInterval(8, viaButtonTap = true)
            vm.setWateringInterval(null, viaButtonTap = false)
            advanceUntilIdle()

            assertEquals(null, stored.value?.wateringIntervalDays)
            coVerify(exactly = 0) { plantRepo.updatePlant(match { it.wateringIntervalDays == 8 }) }
        }

    @Test
    fun `the fresh read inside the lock fixes the stale before value`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stored = MutableStateFlow<Plant?>(plant(wateringIntervalDays = 5))
            val vm = makeVm(stored)

            // The immediate write (6) lands before the tap (7) is ever flushed; the tap's eventual
            // write must log `before = 6` (freshly read at flush time), never the stale `5` the
            // ViewModel's own cached `plant` StateFlow still held when the tap was queued.
            vm.setWateringInterval(6, viaButtonTap = false)
            vm.setWateringInterval(7, viaButtonTap = true)
            advanceUntilIdle()

            assertEquals(7, stored.value?.wateringIntervalDays)
            // The immediate write (5 -> 6) legitimately logs its own row first; the bug this test
            // guards against is the *second* row (the tap's eventual flush) using the stale `5` the
            // cached `plant` StateFlow still held instead of the freshly-written `6`.
            coVerify {
                wateringAdjustmentRepo.addAdjustment(
                    match { it.beforeIntervalDays == 6 && it.afterIntervalDays == 7 }
                )
            }
            coVerify(exactly = 0) {
                wateringAdjustmentRepo.addAdjustment(match { it.beforeIntervalDays == 5 && it.afterIntervalDays == 7 })
            }
        }

    @Test
    fun `leaving the screen mid window still flushes the pending tap edit`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stored = MutableStateFlow<Plant?>(plant(wateringIntervalDays = 7))
            val vm = makeVm(stored)
            val store = ViewModelStore()
            store.put("plant_detail", vm)

            vm.setWateringInterval(12, viaButtonTap = true)
            // Simulates back navigation clearing the screen's ViewModelStore before the 1s quiet
            // window elapses — this cancels viewModelScope (and with it, the pending debounce
            // coroutine) before onCleared() runs.
            store.clear()
            advanceUntilIdle()

            assertEquals(12, stored.value?.wateringIntervalDays)
            coVerify(exactly = 1) { plantRepo.updatePlant(match { it.wateringIntervalDays == 12 }) }
        }

    @Test
    fun `fertilizing taps also coalesce into one write`() = runTest(mainDispatcherRule.testDispatcher) {
        val stored = MutableStateFlow<Plant?>(plant(fertilizingIntervalDays = 30))
        val vm = makeVm(stored)

        vm.setFertilizingInterval(31, viaButtonTap = true)
        vm.setFertilizingInterval(32, viaButtonTap = true)
        advanceUntilIdle()

        assertEquals(32, stored.value?.fertilizingIntervalDays)
        coVerify(exactly = 1) { plantRepo.updatePlant(match { it.fertilizingIntervalDays == 32 }) }
        coVerify(exactly = 0) { plantRepo.updatePlant(match { it.fertilizingIntervalDays == 31 }) }
    }

    @Test
    fun `leaving the screen mid window flushes a pending fertilizing tap edit too`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stored = MutableStateFlow<Plant?>(plant(fertilizingIntervalDays = 30))
            val vm = makeVm(stored)
            val store = ViewModelStore()
            store.put("plant_detail", vm)

            vm.setFertilizingInterval(45, viaButtonTap = true)
            store.clear()
            advanceUntilIdle()

            assertEquals(45, stored.value?.fertilizingIntervalDays)
        }
}
