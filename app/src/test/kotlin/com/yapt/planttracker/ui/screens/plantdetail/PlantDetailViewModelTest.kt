package com.yapt.planttracker.ui.screens.plantdetail

import app.cash.turbine.test
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import android.net.Uri
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.model.GalleryPhotoSource
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        return PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo, plantId, dataStore, quickLogUseCase)
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

    @Test
    fun `applySuggestedInterval updates plant via repo and emits IntervalUpdated event`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
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

        coVerify { plantRepo.updatePlant(match { it.wateringIntervalDays == 14 }) }
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
        val vm = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo, 2L, dataStore, quickLogUseCase)

        vm.careStatus.test {
            val status = awaitItem()
            assertNotNull(status)
            assertEquals(false, status?.isOverdue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestSkip sets showSkipDialog to true`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = makeVm()

        vm.showSkipDialog.test {
            assertFalse(awaitItem())
            vm.requestSkip()
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissSkipDialog sets showSkipDialog to false`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = makeVm()

        vm.requestSkip()
        vm.showSkipDialog.test {
            assertTrue(awaitItem())
            vm.dismissSkipDialog()
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `confirmSkip(1) sets wateringDueDateOverride, keeps wateringIntervalDays unchanged, emits SkipConfirmed`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())

            vm.events.test {
                vm.confirmSkip(1)
                val event = awaitItem()
                assertTrue(event is PlantDetailViewModel.Event.SkipConfirmed)
                val skipEvent = event as PlantDetailViewModel.Event.SkipConfirmed
                assertEquals(1, skipEvent.skippedDays)
                assertEquals(8, skipEvent.proposedInterval)
                cancelAndIgnoreRemainingEvents()
            }

            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantRepo.updatePlant(match { it.wateringDueDateOverride != null && it.wateringIntervalDays == 7 })
        }
    }

    @Test
    fun `confirmSkip(3) pushes due date by 3 days`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())

            vm.events.test {
                vm.confirmSkip(3)
                val event = awaitItem() as PlantDetailViewModel.Event.SkipConfirmed
                assertEquals(3, event.skippedDays)
                assertEquals(10, event.proposedInterval)
                cancelAndIgnoreRemainingEvents()
            }

            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantRepo.updatePlant(match { it.wateringDueDateOverride != null && it.wateringIntervalDays == 7 })
        }
    }

    @Test
    fun `quickWater logs watering, emits message, and applies returned suggestion`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT) } returns
            QuickWaterSuggestion(1L, "Monstera", 9)
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns null
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.quickLogMessage.test {
                vm.quickWater(WateringFeedback.JUST_RIGHT)
                assertEquals(PlantDetailViewModel.QuickLogMessage.Watered("Monstera"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(9, vm.suggestedWateringInterval.value)
        coVerify { quickLogUseCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT) }
    }

    @Test
    fun `quickFertilize logs fertilize via use case and emits message`() = runTest {
        val monstera = plant().copy(fertilizingIntervalDays = 30)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE) } returns "Fertilized Monstera"
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
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE) } returns "Watered and fertilized Monstera"
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
        coEvery { quickLogUseCase.quickLiquidFertilizeWithFeedback(monstera, WateringFeedback.JUST_RIGHT) } returns null
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns null
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.quickLogMessage.test {
                vm.quickLiquidFertilize(WateringFeedback.JUST_RIGHT)
                assertEquals(
                    PlantDetailViewModel.QuickLogMessage.WateredAndFertilized("Monstera"),
                    awaitItem()
                )
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.suggestedWateringInterval.value)
        coVerify { quickLogUseCase.quickLiquidFertilizeWithFeedback(monstera, WateringFeedback.JUST_RIGHT) }
    }

    @Test
    fun `galleryPhotos merges plant photos and care log photos sorted by timestamp desc`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        every { careLogRepo.getLogsForPlant(1L) } returns flowOf(emptyList())

        val plantPhoto = PlantPhoto(id = 1L, plantId = 1L, uri = "file:///plant.jpg", capturedAt = 1000L)
        val careLog = CareLog(
            id = 1L, plantId = 1L,
            careType = CareType.PHOTO,
            loggedAt = 2000L,
            photoUri = "file:///care.jpg"
        )

        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(listOf(plantPhoto))
        every { careLogRepo.getPhotoLogsForPlant(1L) } returns flowOf(listOf(careLog))

        val vm = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo, 1L, dataStore, quickLogUseCase)

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

        val photo = GalleryPhoto(uri = plantPhoto.uri, timestamp = plantPhoto.capturedAt, source = GalleryPhotoSource.FromPlant(plantPhoto))
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

        val photo = GalleryPhoto(uri = plantPhoto.uri, timestamp = plantPhoto.capturedAt, source = GalleryPhotoSource.FromPlant(plantPhoto))
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

        val photo = GalleryPhoto(uri = plantPhoto.uri, timestamp = plantPhoto.capturedAt, source = GalleryPhotoSource.FromPlant(plantPhoto))
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
            careLogRepo.addLog(match {
                it.careType == CareType.PHOTO && it.photoUri == "content://reminder.jpg" && it.plantId == 1L
            })
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
            id = 5L, plantId = 1L,
            careType = CareType.PHOTO,
            loggedAt = 2000L,
            photoUri = "file:///care.jpg"
        )
        every { careLogRepo.getPhotoLogsForPlant(1L) } returns flowOf(listOf(careLog))
        coEvery { careLogRepo.getLogById(5L) } returns careLog
        coEvery { careLogRepo.updateLog(any()) } just runs
        val vm = makeVm()

        val photo = GalleryPhoto(uri = careLog.photoUri!!, timestamp = careLog.loggedAt, source = GalleryPhotoSource.FromCareLog(careLog.id))
        vm.deletePhoto(photo)

        coVerify { careLogRepo.updateLog(match { it.id == 5L && it.photoUri == null }) }
    }
}
