package com.yapt.planttracker.ui.screens.plantdetail

import app.cash.turbine.test
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantPhoto
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
        return PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo, plantId)
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

        vm.suggestedWateringInterval.value = 10
        vm.clearSuggestedInterval()

        vm.suggestedWateringInterval.test {
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
        val vm = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo, 2L)

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
    fun `deletePhoto with care-log URI nulls out the care log photoUri`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val careLog = CareLog(
            id = 5L, plantId = 1L,
            careType = CareType.PHOTO,
            loggedAt = 1000L,
            photoUri = "file:///care.jpg"
        )
        coEvery { careLogRepo.updateLog(any()) } just runs
        val vm = makeVm()
        every { careLogRepo.getLogsForPlant(1L) } returns flowOf(listOf(careLog))

        vm.plant.test {
            awaitItem()
            vm.deletePhoto("file:///care.jpg")
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { careLogRepo.updateLog(match { it.photoUri == null && it.id == 5L }) }
    }

    @Test
    fun `deletePhoto with plant-photo URI calls plantPhotoRepo deletePhoto`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val plantPhoto = PlantPhoto(id = 2L, plantId = 1L, uri = "file:///plant.jpg", capturedAt = 2000L)
        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(listOf(plantPhoto))
        every { careLogRepo.getLogsForPlant(1L) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(1L) } returns flowOf(emptyList())
        coEvery { plantPhotoRepo.deletePhoto(any()) } just runs
        val vm = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo, 1L)

        vm.plant.test {
            awaitItem()
            vm.deletePhoto("file:///plant.jpg")
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantPhotoRepo.deletePhoto(plantPhoto) }
    }

    @Test
    fun `deletePhoto updates coverPhotoUri to next gallery photo when cover is deleted`() = runTest {
        val monstera = plant().copy(coverPhotoUri = "file:///cover.jpg")
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val plantPhoto = PlantPhoto(id = 3L, plantId = 1L, uri = "file:///cover.jpg", capturedAt = 2000L)
        val otherPhoto = PlantPhoto(id = 4L, plantId = 1L, uri = "file:///other.jpg", capturedAt = 1000L)
        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(listOf(plantPhoto, otherPhoto))
        every { careLogRepo.getLogsForPlant(1L) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(1L) } returns flowOf(emptyList())
        coEvery { plantPhotoRepo.deletePhoto(any()) } just runs
        val vm = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo, 1L)

        vm.plant.test {
            awaitItem()
            vm.deletePhoto("file:///cover.jpg")
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.coverPhotoUri == "file:///other.jpg" }) }
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

        val vm = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo, 1L)

        vm.galleryPhotos.test {
            val photos = awaitItem()
            assertEquals(2, photos.size)
            assertEquals(2000L, photos[0].timestamp)
            assertEquals(1000L, photos[1].timestamp)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
