package com.yapt.planttracker.ui.screens.plantdetail

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Reschedule watering (Today/+N days/custom date) and in-app Still moist coverage for
 * [PlantDetailViewModel] (#508, product ADR-0029), split out of `PlantDetailViewModelTest` to keep
 * that file under Detekt's `LargeClass` threshold — mirrors `PlantDetailViewModelSeasonalTest`'s
 * precedent.
 */
class PlantDetailViewModelRescheduleTest {

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
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk {
        every { getRecentForPlant(any(), any()) } returns flowOf(emptyList())
    }

    private fun plant(id: Long = 1L, name: String = "Monstera") = Plant(
        id = id,
        name = name,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun makeVm(plantId: Long = 1L, careLogs: List<CareLog> = emptyList()): PlantDetailViewModel {
        every { careLogRepo.getLogsForPlant(plantId) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plantId) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(plantId) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(plantId) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(plantId) } returns flowOf(emptyList())
        return PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            plantId,
            dataStore,
            quickLogUseCase,
            customReminderRepo,
            plantIssueRepo,
            database,
            wateringAdjustmentRepo
        )
    }

    @Test
    fun `requestReschedule sets showRescheduleDialog to true`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = makeVm()

        vm.showRescheduleDialog.test {
            assertFalse(awaitItem())
            vm.requestReschedule()
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissRescheduleDialog sets showRescheduleDialog to false`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = makeVm()

        vm.requestReschedule()
        vm.showRescheduleDialog.test {
            assertTrue(awaitItem())
            vm.dismissRescheduleDialog()
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- Reschedule watering (#508, product ADR-0029) ----

    @Test
    fun `confirmRescheduleToday sets wateringDueDateOverride to now and closes the dialog`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()
        vm.requestReschedule()

        val before = System.currentTimeMillis()
        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.confirmRescheduleToday()
            cancelAndIgnoreRemainingEvents()
        }
        val after = System.currentTimeMillis()

        assertFalse(vm.showRescheduleDialog.value)
        coVerify {
            plantRepo.updatePlant(
                match {
                    val override = it.wateringDueDateOverride
                    it.wateringIntervalDays == 7 && override != null && override in before..after
                }
            )
        }
    }

    /**
     * A plant last watered 20 days ago on a 7-day interval is clearly overdue, so
     * `maxOf(nextWateringDueAt, now)` collapses to `now` — the resulting override is `now + N days`,
     * asserted against a `[before, after]` wall-clock window bracketing the call rather than an exact
     * timestamp, since both this test and the ViewModel read `System.currentTimeMillis()` independently.
     */
    @Test
    fun `confirmRescheduleRelativeDays(1) pushes the due date 1 day from the effective due date`() = runTest {
        val now = System.currentTimeMillis()
        val monstera = plant().copy(wateringIntervalDays = 7)
        val overdueLog = CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now - TimeUnit.DAYS.toMillis(20))
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm(careLogs = listOf(overdueLog))

        val before = System.currentTimeMillis()
        vm.careStatus.test {
            assertTrue(awaitItem()!!.isOverdue)
            vm.confirmRescheduleRelativeDays(1)
            cancelAndIgnoreRemainingEvents()
        }
        val after = System.currentTimeMillis()

        val oneDayMs = TimeUnit.DAYS.toMillis(1)
        coVerify {
            plantRepo.updatePlant(
                match {
                    val override = it.wateringDueDateOverride
                    it.wateringIntervalDays == 7 &&
                        override != null &&
                        override in (before + oneDayMs)..(after + oneDayMs)
                }
            )
        }
    }

    @Test
    fun `confirmRescheduleRelativeDays(3) pushes the due date 3 days from the effective due date`() = runTest {
        val now = System.currentTimeMillis()
        val monstera = plant().copy(wateringIntervalDays = 7)
        val overdueLog = CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now - TimeUnit.DAYS.toMillis(20))
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm(careLogs = listOf(overdueLog))

        val before = System.currentTimeMillis()
        vm.careStatus.test {
            assertTrue(awaitItem()!!.isOverdue)
            vm.confirmRescheduleRelativeDays(3)
            cancelAndIgnoreRemainingEvents()
        }
        val after = System.currentTimeMillis()

        val threeDaysMs = TimeUnit.DAYS.toMillis(3)
        coVerify {
            plantRepo.updatePlant(
                match {
                    val override = it.wateringDueDateOverride
                    it.wateringIntervalDays == 7 &&
                        override != null &&
                        override in (before + threeDaysMs)..(after + threeDaysMs)
                }
            )
        }
    }

    @Test
    fun `confirmRescheduleCustomDate writes the given date verbatim as wateringDueDateOverride`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()
        val customDate = 1_800_000_000_000L

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.confirmRescheduleCustomDate(customDate)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantRepo.updatePlant(
                match { it.wateringDueDateOverride == customDate && it.wateringIntervalDays == 7 }
            )
        }
    }

    @Test
    fun `reschedule options never touch wateringBaseIntervalDays, wateringConfidence, or watering_adjustments`() =
        runTest {
            val monstera = plant().copy(
                wateringIntervalDays = 7,
                wateringBaseIntervalDays = 7.0,
                wateringConfidence = 3
            )
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                vm.confirmRescheduleToday()
                vm.confirmRescheduleRelativeDays(2)
                vm.confirmRescheduleCustomDate(1_800_000_000_000L)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 3) {
                plantRepo.updatePlant(
                    match { it.wateringBaseIntervalDays == 7.0 && it.wateringConfidence == 3 }
                )
            }
            coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
        }

    @Test
    fun `reschedule options never emit an Event that could feed the interval-suggestion dialog`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.events.test {
                vm.confirmRescheduleToday()
                vm.confirmRescheduleRelativeDays(1)
                vm.confirmRescheduleCustomDate(1_800_000_000_000L)
                expectNoEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.suggestedWateringInterval.value)
    }

    // ---- Still moist, in-app (#508, product ADR-0029) ----

    @Test
    fun `recordStillMoist routes through QuickLogUseCase#recordStillMoistCheck and emits StillMoistChecked`() =
        runTest {
            val monstera = plant()
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { quickLogUseCase.recordStillMoistCheck(monstera) } returns true
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                vm.quickLogMessage.test {
                    vm.recordStillMoist()
                    assertEquals(
                        PlantDetailViewModel.QuickLogMessage.StillMoistChecked("Monstera"),
                        awaitItem()
                    )
                    cancelAndIgnoreRemainingEvents()
                }
                cancelAndIgnoreRemainingEvents()
            }

            coVerify { quickLogUseCase.recordStillMoistCheck(monstera) }
        }

    @Test
    fun `recordStillMoist emits AlreadyCheckedToday when the use case returns false`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.recordStillMoistCheck(monstera) } returns false
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.quickLogMessage.test {
                vm.recordStillMoist()
                assertEquals(
                    PlantDetailViewModel.QuickLogMessage.AlreadyCheckedToday("Monstera"),
                    awaitItem()
                )
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
