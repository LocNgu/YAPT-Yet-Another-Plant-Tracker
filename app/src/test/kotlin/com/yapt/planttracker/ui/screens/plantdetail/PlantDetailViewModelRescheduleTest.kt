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
 * Reschedule watering (Today/+N days/custom date) coverage for [PlantDetailViewModel] (#508 product
 * ADR-0029, reshaped by #586 product ADR-0030, made model-neutral again by #738 product ADR-0039),
 * split out of `PlantDetailViewModelTest` to keep that file under Detekt's `LargeClass` threshold —
 * mirrors `PlantDetailViewModelSeasonalTest`'s precedent.
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
    fun `requestReschedule opens the date dialog directly, with no reason prompt`() = runTest {
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

    // ---- Reschedule watering (#508, product ADR-0029; model-neutral since #738, product ADR-0039) ----

    @Test
    fun `confirmRescheduleToday delegates to recordReschedule with now and closes the dialog`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.recordReschedule(any(), any()) } just runs
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
            quickLogUseCase.recordReschedule(monstera, match { it in before..after })
        }
    }

    @Test
    fun `relative date option commits the timestamp shown in the dialog`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.recordReschedule(any(), any()) } just runs
        val vm = makeVm()
        val shownRelativeDueAt = 1_800_000_000_000L

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.confirmRescheduleRelativeDate(shownRelativeDueAt)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { quickLogUseCase.recordReschedule(monstera, shownRelativeDueAt) }
    }

    @Test
    fun `confirmRescheduleCustomDate delegates to recordReschedule with the given date verbatim`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.recordReschedule(any(), any()) } just runs
        val vm = makeVm()
        val customDate = 1_800_000_000_000L

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.confirmRescheduleCustomDate(customDate)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.recordReschedule(monstera, customDate) }
    }

    /**
     * #738 (product ADR-0039): every reschedule option — whichever date option was tapped — writes
     * only `Plant.wateringDueDateOverride` via `QuickLogUseCase.recordReschedule()`. It never touches
     * `wateringIntervalDays`/`wateringBaseIntervalDays`/`wateringConfidence` directly (those columns
     * are never even passed to the mocked use case) and never writes a `watering_adjustments` row —
     * the single surviving contract for the whole Reschedule flow, broadened from the pre-#738 test of
     * the same name once the reason-prompt code it also covered was removed.
     */
    @Test
    fun `every reschedule option delegates to recordReschedule and touches nothing else`() = runTest {
        val monstera = plant().copy(
            wateringIntervalDays = 7,
            wateringBaseIntervalDays = 7.0,
            wateringConfidence = 3
        )
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.recordReschedule(any(), any()) } just runs
        val vm = makeVm()
        val customDate = 1_800_000_000_000L

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.confirmRescheduleToday()
            vm.confirmRescheduleRelativeDate(customDate + TimeUnit.DAYS.toMillis(1))
            vm.confirmRescheduleCustomDate(customDate)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 3) { quickLogUseCase.recordReschedule(monstera, any()) }
        coVerify(exactly = 1) { quickLogUseCase.recordReschedule(monstera, customDate) }
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
    }

    @Test
    fun `reschedule options never emit an Event that could feed the interval-suggestion dialog`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.recordReschedule(any(), any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.events.test {
                vm.confirmRescheduleToday()
                vm.confirmRescheduleRelativeDate(1_800_000_000_000L + TimeUnit.DAYS.toMillis(1))
                vm.confirmRescheduleCustomDate(1_800_000_000_000L)
                expectNoEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.suggestedWateringInterval.value)
    }

    // ---- Reschedule delta + revert (#630) ----

    @Test
    fun `revertReschedule clears wateringDueDateOverride and emits RescheduleReverted with the prior value`() =
        runTest {
            val monstera = plant().copy(wateringIntervalDays = 7, wateringDueDateOverride = 1_800_000_000_000L)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                vm.events.test {
                    vm.revertReschedule()
                    val event = awaitItem() as PlantDetailViewModel.Event.RescheduleReverted
                    assertEquals(1_800_000_000_000L, event.previousOverrideAtMillis)
                    cancelAndIgnoreRemainingEvents()
                }
                cancelAndIgnoreRemainingEvents()
            }

            coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == null }) }
        }

    @Test
    fun `revertReschedule never touches interval, base interval, confidence, or watering_adjustments`() =
        runTest {
            val monstera = plant().copy(
                wateringIntervalDays = 7,
                wateringBaseIntervalDays = 7.0,
                wateringConfidence = 3,
                wateringDueDateOverride = 1_800_000_000_000L
            )
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                vm.revertReschedule()
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                plantRepo.updatePlant(
                    match {
                        it.wateringIntervalDays == 7 &&
                            it.wateringBaseIntervalDays == 7.0 &&
                            it.wateringConfidence == 3
                    }
                )
            }
            coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
        }

    @Test
    fun `revertReschedule is a no-op when there is no active override`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.events.test {
                vm.revertReschedule()
                expectNoEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `undoRevertReschedule restores the prior wateringDueDateOverride as-is`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7, wateringDueDateOverride = null)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.undoRevertReschedule(1_800_000_000_000L)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == 1_800_000_000_000L }) }
    }
}
