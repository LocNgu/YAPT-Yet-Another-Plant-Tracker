package com.yapt.planttracker.ui.screens.plantdetail

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import app.cash.turbine.test
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * `quickWater`/`quickFertilize`/`quickRepot`/`quickLiquidFertilize`/`saveReminderPhoto`/`savePhotoLog`
 * coverage for [PlantDetailViewModel] (#586/#658/#694), split out of `PlantDetailViewModelTest` to keep
 * that file under Detekt's `LargeClass` threshold — mirrors `PlantDetailViewModelRescheduleTest`'s
 * precedent.
 */
class PlantDetailViewModelQuickActionsTest {

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
        coEvery { addAdjustment(any()) } returns 1L
    }

    private fun plant(id: Long = 1L, name: String = "Monstera") = Plant(
        id = id,
        name = name,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun makeVm(plantId: Long = 1L): PlantDetailViewModel {
        every { careLogRepo.getLogsForPlant(plantId) } returns flowOf(emptyList())
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
    fun `quickWater logs watering, emits message, and applies returned suggestion`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickWaterWithReason(monstera, null, any()) } returns
            QuickLogUseCase.QuickLogOutcome(
                message = "Watered Monstera",
                logged = true,
                suggestion = QuickWaterSuggestion(1L, "Monstera", 9, 9, 9.0)
            )
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns null
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.quickLogMessage.test {
                vm.quickWater(reason = null)
                assertEquals(PlantDetailViewModel.QuickLogMessage.Watered("Monstera"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(9, vm.suggestedWateringInterval.value)
        coVerify { quickLogUseCase.quickWaterWithReason(monstera, null, any()) }
    }

    @Test
    fun `quickWater with askBeforeChangingIntervals off applies the suggestion silently`() = runTest {
        every { dataStore.data } returns flowOf(
            preferencesOf(
                SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS to false,
                SettingsKeys.SEASONAL_AMPLITUDE to "OFF"
            )
        )
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickWaterWithReason(monstera, null, any()) } returns
            QuickLogUseCase.QuickLogOutcome(
                message = "Watered Monstera",
                logged = true,
                suggestion = QuickWaterSuggestion(1L, "Monstera", 9, 9, 9.0)
            )
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns null
        coEvery { quickLogUseCase.applyWateringIntervalSuggestion(monstera, 9, 9, 9.0) } returns
            QuickLogUseCase.IntervalApplyResult(
                previousEffectiveIntervalDays = 7,
                previousBaseIntervalDays = null,
                newEffectiveIntervalDays = 9
            )
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.events.test {
                vm.quickWater(reason = null)
                val event = awaitItem()
                assertEquals(PlantDetailViewModel.Event.SilentIntervalApplied(7, null, 9), event)
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        // Never shows the dialog.
        assertEquals(null, vm.suggestedWateringInterval.value)
        coVerify { quickLogUseCase.applyWateringIntervalSuggestion(monstera, 9, 9, 9.0) }
    }

    /**
     * Regression guard for the silent-apply half of #718 (technical ADR-0027). The precise base must
     * travel into [QuickLogUseCase.applyWateringIntervalSuggestion] even when it happens to be a whole
     * number: passing `null` there instead makes that function re-derive the base as
     * `deseasonalize(effectiveInterval)`, and since `effectiveInterval` is already
     * `round(base x season)`, the round-trip loses the fraction and ratchets the stored base.
     * Amplitude is left at its graduated STANDARD default so the seasonal conversion is actually live
     * -- the Off path ignores the base entirely and cannot catch this.
     */
    @Test
    fun `silent apply forwards a whole-number precise base instead of re-deriving it (#718)`() = runTest {
        every { dataStore.data } returns flowOf(
            preferencesOf(SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS to false)
        )
        val monstera = plant().copy(wateringIntervalDays = 7, wateringBaseIntervalDays = 7.0)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickWaterWithReason(monstera, null, any()) } returns
            QuickLogUseCase.QuickLogOutcome(
                message = "Watered Monstera",
                logged = true,
                suggestion = QuickWaterSuggestion(1L, "Monstera", 7, 8, 7.0)
            )
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns null
        coEvery { quickLogUseCase.applyWateringIntervalSuggestion(monstera, 7, any(), 7.0) } returns
            QuickLogUseCase.IntervalApplyResult(
                previousEffectiveIntervalDays = 7,
                previousBaseIntervalDays = 7.0,
                newEffectiveIntervalDays = 8
            )
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.quickWater(reason = null)
            cancelAndIgnoreRemainingEvents()
        }

        // 7.0, never null -- the effective-space value is deliberately not the source of the base.
        coVerify { quickLogUseCase.applyWateringIntervalSuggestion(monstera, 7, any(), 7.0) }
    }

    @Test
    fun `undoSilentIntervalApply reverts wateringIntervalDays to the given value`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 9)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.undoSilentIntervalApply(7, null)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.wateringIntervalDays == 7 }) }
    }

    // undoSilentIntervalApply's SILENT_APPLY_UNDONE adjustment-row coverage lives in
    // PlantDetailViewModelSeasonalTest, to keep this file under Detekt's LargeClass threshold.

    @Test
    fun `quickFertilize logs fertilize via use case and emits message`() = runTest {
        val monstera = plant().copy(fertilizingIntervalDays = 30)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE, any()) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Fertilized Monstera", logged = true, waterPaired = false)
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns null
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.quickLogMessage.test {
                vm.quickFertilize()
                assertEquals(PlantDetailViewModel.QuickLogMessage.Fertilized("Monstera"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE, any()) }
    }

    @Test
    fun `quickFertilize on a liquid-fertilizer plant emits the combined message`() = runTest {
        val monstera = plant().copy(useLiquidFertilizer = true, fertilizingIntervalDays = 30)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE, any()) } returns
            QuickLogUseCase.QuickLogOutcome(
                message = "Watered and fertilized Monstera",
                logged = true,
                waterPaired = true
            )
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns null
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.quickLogMessage.test {
                vm.quickFertilize()
                assertEquals(
                    PlantDetailViewModel.QuickLogMessage.WateredAndFertilized("Monstera"),
                    awaitItem()
                )
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE, any()) }
    }

    @Test
    fun `quickRepot delegates to shared use case and emits message`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickLog(monstera, CareType.REPOT, any()) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Repotted Monstera", logged = true)
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns null
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.quickLogMessage.test {
                vm.quickRepot()
                assertEquals(PlantDetailViewModel.QuickLogMessage.Repotted("Monstera"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.quickLog(monstera, CareType.REPOT, any()) }
    }

    // #694: the Repot tab's date picker forwards its picked date straight through to the shared
    // use case, mirroring quickWater/quickLiquidFertilize's existing loggedAt threading.
    @Test
    fun `quickRepot forwards the picked loggedAt to the shared use case`() = runTest {
        val monstera = plant()
        val pickedLoggedAt = 123_456_789L
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickLog(monstera, CareType.REPOT, pickedLoggedAt) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Repotted Monstera", logged = true)
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns null
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.quickLogMessage.test {
                vm.quickRepot(pickedLoggedAt)
                assertEquals(PlantDetailViewModel.QuickLogMessage.Repotted("Monstera"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.quickLog(monstera, CareType.REPOT, pickedLoggedAt) }
    }

    @Test
    fun `quickLiquidFertilize logs paired care and emits combined message`() = runTest {
        val monstera = plant().copy(
            useLiquidFertilizer = true,
            fertilizingIntervalDays = 30,
            wateringIntervalDays = 7
        )
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickLiquidFertilizeWithReason(monstera, null, any()) } returns
            QuickLogUseCase.QuickLogOutcome(
                message = "Watered and fertilized Monstera",
                logged = true,
                waterPaired = true
            )
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns null
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.quickLogMessage.test {
                vm.quickLiquidFertilize(reason = null)
                assertEquals(
                    PlantDetailViewModel.QuickLogMessage.WateredAndFertilized("Monstera"),
                    awaitItem()
                )
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.suggestedWateringInterval.value)
        coVerify { quickLogUseCase.quickLiquidFertilizeWithReason(monstera, null, any()) }
    }

    @Test
    fun `saveReminderPhoto adds a PHOTO care log, plant_photos row, and updates cover`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantPhotoRepo.addPhoto(any()) } returns 1L
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()
        val uri: Uri = mockk()
        every { uri.toString() } returns "content://reminder.jpg"

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.saveReminderPhoto(uri)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantPhotoRepo.addPhoto(match { it.uri == "content://reminder.jpg" && it.plantId == 1L })
        }
        coVerify {
            careLogRepo.addLog(
                match {
                    it.careType == CareType.PHOTO && it.photoUri == "content://reminder.jpg" && it.plantId == 1L
                }
            )
        }
        coVerify {
            plantRepo.updatePlant(match { it.coverPhotoUri == "content://reminder.jpg" })
        }
    }

    // #694: the Photo tab's Add-photo sheet logs in place rather than navigating to
    // AddCareLogScreen — see product ADR-0038. Unlike saveReminderPhoto, this never writes a
    // plant_photos row (the unified PhotoGallery already merges care-log photos, technical ADR-0015) and
    // carries the sheet's own picked loggedAt rather than always "now".
    @Test
    fun `savePhotoLog logs a PHOTO care log at the picked date, updates cover, and skips plantPhotoRepository`() =
        runTest {
            val monstera = plant()
            val pickedLoggedAt = 111_222_333L
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { careLogRepo.addLog(any()) } returns 1L
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = makeVm()
            val uri: Uri = mockk()
            every { uri.toString() } returns "content://add-photo.jpg"

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                vm.savePhotoLog(uri, pickedLoggedAt)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                careLogRepo.addLog(
                    match {
                        it.careType == CareType.PHOTO &&
                            it.photoUri == "content://add-photo.jpg" &&
                            it.plantId == 1L &&
                            it.loggedAt == pickedLoggedAt
                    }
                )
            }
            coVerify {
                plantRepo.updatePlant(match { it.coverPhotoUri == "content://add-photo.jpg" })
            }
            coVerify(exactly = 0) { plantPhotoRepo.addPhoto(any()) }
        }
}
