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
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.model.GalleryPhotoSource
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
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
        coEvery { addAdjustment(any()) } returns 1L
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
        coEvery { quickLogUseCase.applyWateringIntervalSuggestion(monstera, null, 14, null) } returns
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

        coVerify { quickLogUseCase.applyWateringIntervalSuggestion(monstera, null, 14, null) }
    }

    // Math-correctness coverage for recordWateringSuggestionDismissal's confidence bump and
    // WateringAdjustment row shape now lives in QuickLogUseCaseDismissalTest, against
    // QuickLogUseCase.recordWateringSuggestionDismissal() directly (#674) — this is a thin
    // delegation/smoke test, mirroring applySuggestedInterval's above.
    @Test
    fun `dismissSuggestedInterval delegates to QuickLogUseCase with the resolved plant`() = runTest {
        val monstera = plant().copy(wateringConfidence = 1)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.recordWateringSuggestionDismissal(monstera) } returns
            monstera.copy(wateringConfidence = 2)
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.dismissSuggestedInterval()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.recordWateringSuggestionDismissal(monstera) }
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
    fun `setDormancyWindow auto persists a wrapping range and clears both months`() = runTest {
        val current = plant().copy(dormancyStartMonth = 11, dormancyEndMonth = 2)
        every { plantRepo.getPlantById(1L) } returns flowOf(current)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(current, awaitItem())
            vm.setDormancyWindow(12, 3, 35)
            vm.setDormancyWindow(null, null)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantRepo.updatePlant(
                match {
                    it.dormancyStartMonth == 12 && it.dormancyEndMonth == 3 &&
                        it.dormantWateringIntervalDays == 35
                }
            )
        }
        coVerify {
            plantRepo.updatePlant(
                match {
                    it.dormancyStartMonth == null && it.dormancyEndMonth == null &&
                        it.dormantWateringIntervalDays == null
                }
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `rapid dormancy writes commit in order using the latest stored plant`() = runTest {
        val initial = plant().copy(dormancyStartMonth = 11, dormancyEndMonth = 2)
        val stored = MutableStateFlow<Plant?>(initial)
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val writes = mutableListOf<Pair<Int?, Int?>>()
        every { plantRepo.getPlantById(1L) } returns stored
        coEvery { plantRepo.updatePlant(any()) } coAnswers {
            val updated = firstArg<Plant>()
            if (writes.isEmpty()) releaseFirstWrite.await()
            writes += updated.dormancyStartMonth to updated.dormancyEndMonth
            stored.value = updated
        }
        val vm = makeVm()

        vm.plant.test {
            assertEquals(initial, awaitItem())
            vm.setDormancyWindow(10, 2)
            vm.setDormancyWindow(10, 3)
            assertEquals(initial, stored.value)
            releaseFirstWrite.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(10 to 2, 10 to 3), writes)
            assertEquals(10, stored.value?.dormancyStartMonth)
            assertEquals(3, stored.value?.dormancyEndMonth)
            cancelAndIgnoreRemainingEvents()
        }
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
    fun `toggleFertilizingSeason persists the toggled set via repo`() = runTest {
        val monstera = plant().copy(fertilizingSeasons = setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER))
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.toggleFertilizingSeason(FertilizingSeason.AUTUMN)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantRepo.updatePlant(
                match {
                    it.fertilizingSeasons ==
                        setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER, FertilizingSeason.AUTUMN)
                }
            )
        }
    }

    @Test
    fun `toggleFertilizingSeason rejects a toggle that would empty the set`() = runTest {
        val monstera = plant().copy(fertilizingSeasons = setOf(FertilizingSeason.SPRING))
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.toggleFertilizingSeason(FertilizingSeason.SPRING)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
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

    // #679 review round 1: previousWateringBefore() itself was untested — it's a thin suspend wrapper
    // around CareLogRepository.getLastWateringBefore(), but PlantDetailScreen's date-picker onConfirm
    // callbacks rely on it (not PlantCareStatus.lastWateredAt) to find the chosen date's real
    // chronological predecessor.
    @Test
    fun `previousWateringBefore delegates to careLogRepository getLastWateringBefore`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val before = 5_000L
        val predecessor = CareLog(
            id = 42L,
            plantId = 1L,
            careType = CareType.WATER,
            loggedAt = 1_000L
        )
        coEvery { careLogRepo.getLastWateringBefore(1L, before) } returns predecessor
        val vm = makeVm()

        val result = vm.previousWateringBefore(before)

        assertEquals(1_000L, result)
        coVerify { careLogRepo.getLastWateringBefore(1L, before) }
    }

    @Test
    fun `previousWateringBefore returns null when no earlier watering exists`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val before = 5_000L
        coEvery { careLogRepo.getLastWateringBefore(1L, before) } returns null
        val vm = makeVm()

        val result = vm.previousWateringBefore(before)

        assertNull(result)
        coVerify { careLogRepo.getLastWateringBefore(1L, before) }
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

    // requestReschedule/confirmReschedule* coverage lives in
    // PlantDetailViewModelRescheduleTest (#508/#586/#738), to keep this file under Detekt's LargeClass threshold.

    // quickWater/quickFertilize/quickRepot/quickLiquidFertilize coverage lives in
    // PlantDetailViewModelQuickActionsTest (#586/#658/#694), to keep this file under Detekt's
    // LargeClass threshold.

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

    // saveReminderPhoto/savePhotoLog coverage lives in PlantDetailViewModelQuickActionsTest
    // (#694), to keep this file under Detekt's LargeClass threshold.

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
