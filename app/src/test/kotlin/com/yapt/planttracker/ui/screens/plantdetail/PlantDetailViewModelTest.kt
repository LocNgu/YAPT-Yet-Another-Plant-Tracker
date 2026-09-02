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
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.model.GalleryPhotoSource
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.model.PlantPhoto
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class PlantDetailViewModelTest {

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
    }

    // Only reportIssue() touches withTransaction, and no test in this file exercises it
    // (see PlantDetailViewModelPlantIssueTest), so a bare mock is never invoked here.
    private val database: PlantDatabase = mockk()

    private fun plant(id: Long = 1L, name: String = "Monstera") = Plant(
        id = id,
        name = name,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun makeVm(
        plantId: Long = 1L,
        customReminders: List<CustomReminder> = emptyList(),
        activeIssues: List<PlantIssue> = emptyList()
    ): PlantDetailViewModel {
        every { careLogRepo.getLogsForPlant(plantId) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(plantId) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(plantId) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(plantId) } returns flowOf(customReminders)
        every { plantIssueRepo.getActiveIssuesForPlant(plantId) } returns flowOf(activeIssues)
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
    fun `plant exists emits the plant in StateFlow`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = makeVm()

        vm.plant.test {
            val emitted = awaitItem()
            assertNotNull(emitted)
            assertEquals("Monstera", emitted?.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no plant found emits null careStatus`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(null)
        val vm = makeVm()

        vm.careStatus.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Math-correctness coverage for applySuggestedInterval's write (base dual-write, effective-space
    // conversion, confidence math, WateringAdjustment row shape) now lives in QuickLogUseCaseTest,
    // against QuickLogUseCase.applyWateringIntervalSuggestion() directly (#631) — this is a thin
    // delegation/smoke test, mirroring how quickWater/quickFertilize are tested below.
    @Test
    fun `applySuggestedInterval delegates to QuickLogUseCase and emits IntervalUpdated event`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.applyWateringIntervalSuggestion(monstera, null, 14) } returns
            QuickLogUseCase.IntervalApplyResult(
                previousEffectiveIntervalDays = 7,
                previousBaseIntervalDays = null,
                newEffectiveIntervalDays = 14
            )
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())

            vm.events.test {
                vm.applySuggestedInterval(14)
                assertEquals(PlantDetailViewModel.Event.IntervalUpdated, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.applyWateringIntervalSuggestion(monstera, null, 14) }
    }

    @Test
    fun `setWateringInterval persists the new interval via repo`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.setWateringInterval(10)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.wateringIntervalDays == 10 }) }
    }

    @Test
    fun `setWateringInterval null clears the schedule`() = runTest {
        val scheduled = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(scheduled)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(scheduled, awaitItem())
            vm.setWateringInterval(null)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.wateringIntervalDays == null }) }
    }

    @Test
    fun `setFertilizingInterval persists the new interval via repo`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.setFertilizingInterval(21)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.fertilizingIntervalDays == 21 }) }
    }

    @Test
    fun `setLiquidFertilizer persists the toggle via repo`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.setLiquidFertilizer(true)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.useLiquidFertilizer }) }
    }

    @Test
    fun `clearSuggestedInterval sets suggestedWateringInterval to null`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = makeVm()

        vm.suggestedWateringInterval.test {
            assertNull(awaitItem())
            vm.suggestedWateringInterval.value = 10
            assertEquals(10, awaitItem())
            vm.clearSuggestedInterval()
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `plant with watering interval and no logs has no overdue status`() = runTest {
        val plantWithInterval = Plant(
            id = 2L,
            name = "Fern",
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
        every { plantRepo.getPlantById(2L) } returns flowOf(plantWithInterval)
        every { careLogRepo.getLogsForPlant(2L) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(2L) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(2L) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(2L) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(2L) } returns flowOf(emptyList())
        val vm = PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            2L,
            dataStore,
            quickLogUseCase,
            customReminderRepo,
            plantIssueRepo,
            database,
            wateringAdjustmentRepo
        )

        vm.careStatus.test {
            val status = awaitItem()
            assertNotNull(status)
            assertEquals(false, status?.isOverdue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // requestReschedule/chooseRescheduleReason/confirmReschedule* coverage lives in
    // PlantDetailViewModelRescheduleTest (#508/#586), to keep this file under Detekt's LargeClass threshold.

    @Test
    fun `quickWater logs watering, emits message, and applies returned suggestion`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickWaterWithReason(monstera, null) } returns
            QuickLogUseCase.QuickLogOutcome(
                message = "Watered Monstera",
                logged = true,
                suggestion = QuickWaterSuggestion(1L, "Monstera", 9, 9)
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
        coVerify { quickLogUseCase.quickWaterWithReason(monstera, null) }
    }

    @Test
    fun `quickWater with ADAPTIVE_WATERING on and askBeforeChangingIntervals off applies the suggestion silently`() = runTest {
        every { dataStore.data } returns flowOf(
            preferencesOf(
                FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true,
                SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS to false
            )
        )
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickWaterWithReason(monstera, null) } returns
            QuickLogUseCase.QuickLogOutcome(
                message = "Watered Monstera",
                logged = true,
                suggestion = QuickWaterSuggestion(1L, "Monstera", 9, 9)
            )
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns null
        coEvery { quickLogUseCase.applyWateringIntervalSuggestion(monstera, 9, 9) } returns
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
        coVerify { quickLogUseCase.applyWateringIntervalSuggestion(monstera, 9, 9) }
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
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE) } returns
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

        coVerify { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE) }
    }

    @Test
    fun `quickFertilize on a liquid-fertilizer plant emits the combined message`() = runTest {
        val monstera = plant().copy(useLiquidFertilizer = true, fertilizingIntervalDays = 30)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Watered and fertilized Monstera", logged = true, waterPaired = true)
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

        coVerify { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE) }
    }

    @Test
    fun `quickLiquidFertilize logs paired care and emits combined message`() = runTest {
        val monstera = plant().copy(
            useLiquidFertilizer = true,
            fertilizingIntervalDays = 30,
            wateringIntervalDays = 7
        )
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickLiquidFertilizeWithReason(monstera, null) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Watered and fertilized Monstera", logged = true, waterPaired = true)
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
        coVerify { quickLogUseCase.quickLiquidFertilizeWithReason(monstera, null) }
    }

    @Test
    fun `galleryPhotos merges plant photos and care log photos sorted by timestamp desc`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        every { careLogRepo.getLogsForPlant(1L) } returns flowOf(emptyList())

        val plantPhoto = PlantPhoto(id = 1L, plantId = 1L, uri = "file:///plant.jpg", capturedAt = 1000L)
        val careLog = CareLog(
            id = 1L,
            plantId = 1L,
            careType = CareType.PHOTO,
            loggedAt = 2000L,
            photoUri = "file:///care.jpg"
        )

        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(listOf(plantPhoto))
        every { careLogRepo.getPhotoLogsForPlant(1L) } returns flowOf(listOf(careLog))
        every { customReminderRepo.getRemindersForPlant(1L) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(1L) } returns flowOf(emptyList())

        val vm = PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            1L,
            dataStore,
            quickLogUseCase,
            customReminderRepo,
            plantIssueRepo,
            database,
            wateringAdjustmentRepo
        )

        vm.galleryPhotos.test {
            val photos = awaitItem()
            assertEquals(2, photos.size)
            assertEquals(2000L, photos[0].timestamp)
            assertEquals(1000L, photos[1].timestamp)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deletePhoto plant photo calls deletePhoto on plantPhotoRepository`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val plantPhoto = PlantPhoto(id = 1L, plantId = 1L, uri = "file:///plant.jpg", capturedAt = 1000L)
        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(listOf(plantPhoto))
        coEvery { plantPhotoRepo.deletePhoto(plantPhoto) } just runs
        coEvery { plantPhotoRepo.getPhotosForPlantOnce(1L) } returns emptyList()
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        val photo =
            GalleryPhoto(
                uri = plantPhoto.uri,
                timestamp = plantPhoto.capturedAt,
                source = GalleryPhotoSource.FromPlant(plantPhoto)
            )
        vm.deletePhoto(photo)

        coVerify { plantPhotoRepo.deletePhoto(plantPhoto) }
    }

    @Test
    fun `deletePhoto cover plant photo updates coverPhotoUri to next most-recent`() = runTest {
        val nextPhoto = PlantPhoto(id = 2L, plantId = 1L, uri = "file:///next.jpg", capturedAt = 500L)
        val monstera = plant().copy(coverPhotoUri = "file:///plant.jpg")
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val plantPhoto = PlantPhoto(id = 1L, plantId = 1L, uri = "file:///plant.jpg", capturedAt = 1000L)
        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(listOf(plantPhoto))
        coEvery { plantPhotoRepo.deletePhoto(plantPhoto) } just runs
        coEvery { plantPhotoRepo.getPhotosForPlantOnce(1L) } returns listOf(nextPhoto)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        val photo =
            GalleryPhoto(
                uri = plantPhoto.uri,
                timestamp = plantPhoto.capturedAt,
                source = GalleryPhotoSource.FromPlant(plantPhoto)
            )
        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.deletePhoto(photo)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.coverPhotoUri == nextPhoto.uri }) }
    }

    @Test
    fun `deletePhoto last plant photo clears coverPhotoUri to null`() = runTest {
        val monstera = plant().copy(coverPhotoUri = "file:///plant.jpg")
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val plantPhoto = PlantPhoto(id = 1L, plantId = 1L, uri = "file:///plant.jpg", capturedAt = 1000L)
        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(listOf(plantPhoto))
        coEvery { plantPhotoRepo.deletePhoto(plantPhoto) } just runs
        coEvery { plantPhotoRepo.getPhotosForPlantOnce(1L) } returns emptyList()
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        val photo =
            GalleryPhoto(
                uri = plantPhoto.uri,
                timestamp = plantPhoto.capturedAt,
                source = GalleryPhotoSource.FromPlant(plantPhoto)
            )
        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.deletePhoto(photo)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.coverPhotoUri == null }) }
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

    @Test
    fun `deletePhoto care log photo nulls out photoUri via updateLog`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val careLog = CareLog(
            id = 5L,
            plantId = 1L,
            careType = CareType.PHOTO,
            loggedAt = 2000L,
            photoUri = "file:///care.jpg"
        )
        every { careLogRepo.getPhotoLogsForPlant(1L) } returns flowOf(listOf(careLog))
        coEvery { careLogRepo.getLogById(5L) } returns careLog
        coEvery { careLogRepo.updateLog(any()) } just runs
        val vm = makeVm()

        val photo =
            GalleryPhoto(
                uri = careLog.photoUri!!,
                timestamp = careLog.loggedAt,
                source = GalleryPhotoSource.FromCareLog(careLog.id)
            )
        vm.deletePhoto(photo)

        coVerify { careLogRepo.updateLog(match { it.id == 5L && it.photoUri == null }) }
    }

    // ---- Custom reminders (#232) ----

    @Test
    fun `addCustomReminder inserts a new reminder for this plant`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { customReminderRepo.addReminder(any()) } returns 1L
        val vm = makeVm()

        vm.addCustomReminder("Neem oil treatment", 7)

        coVerify {
            customReminderRepo.addReminder(
                match { it.plantId == 1L && it.name == "Neem oil treatment" && it.intervalDays == 7 }
            )
        }
    }

    @Test
    fun `updateCustomReminder persists the new name and interval without touching lastDoneAt`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        val existing = CustomReminder(id = 9L, plantId = 1L, name = "Old name", intervalDays = 7, lastDoneAt = 500L)
        coEvery { customReminderRepo.updateReminder(any()) } just runs
        val vm = makeVm()

        vm.updateCustomReminder(existing, "New name", 14)

        coVerify {
            customReminderRepo.updateReminder(
                match { it.id == 9L && it.name == "New name" && it.intervalDays == 14 && it.lastDoneAt == 500L }
            )
        }
    }

    @Test
    fun `deleteCustomReminder removes it via the repo`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        val reminder = CustomReminder(id = 9L, plantId = 1L, name = "Neem oil", intervalDays = 7)
        coEvery { customReminderRepo.deleteReminder(any()) } just runs
        val vm = makeVm()

        vm.deleteCustomReminder(reminder)

        coVerify { customReminderRepo.deleteReminder(reminder) }
    }

    @Test
    fun `markCustomReminderDone writes a CUSTOM care log linked to the reminder and resets lastDoneAt`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        val reminder = CustomReminder(id = 9L, plantId = 1L, name = "Neem oil", intervalDays = 7, lastDoneAt = null)
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { customReminderRepo.updateReminder(any()) } just runs
        val vm = makeVm()

        vm.markCustomReminderDone(reminder)

        coVerify {
            careLogRepo.addLog(
                match { it.plantId == 1L && it.careType == CareType.CUSTOM && it.customReminderId == 9L }
            )
        }
        coVerify { customReminderRepo.updateReminder(match { it.id == 9L && it.lastDoneAt != null }) }
    }
}
