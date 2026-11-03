package com.yapt.planttracker.ui.screens.plantdetail

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.model.GalleryPhotoSource
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantPhoto
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
import org.junit.Rule
import org.junit.Test

/**
 * Cover-photo fallback when a cover-eligible photo is deleted (#323, product ADR-0078). Split from
 * `PlantDetailViewModelTest` (Detekt `LargeClass`). Its own `makeVm()` deliberately does not re-stub the photo
 * flows, which `setup()` fills in.
 */
class PlantDetailViewModelCoverDeleteTest {

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
    private val database: PlantDatabase = mockk()

    private fun plant() = Plant(id = 1L, name = "Monstera", createdAt = 0L, updatedAt = 0L)

    private fun makeVm(): PlantDetailViewModel {
        every { careLogRepo.getLogsForPlant(1L) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(1L) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(1L) } returns flowOf(emptyList())
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
            wateringAdjustmentRepo
        )
    }

    private fun setup(
        cover: String?,
        plantPhotos: List<PlantPhoto> = emptyList(),
        logs: List<CareLog> = emptyList()
    ) {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant().copy(coverPhotoUri = cover))
        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(plantPhotos)
        coEvery { plantPhotoRepo.deletePhoto(any()) } just runs
        coEvery { plantPhotoRepo.getPhotosForPlantOnce(1L) } returns plantPhotos
        every { careLogRepo.getPhotoLogsForPlant(1L) } returns flowOf(logs)
        coEvery { careLogRepo.getLogById(any()) } answers { logs.firstOrNull { it.id == firstArg<Long>() } }
        coEvery { careLogRepo.updateLog(any()) } just runs
        coEvery { careLogRepo.deleteLog(any()) } just runs
        coEvery { plantRepo.updateCoverPhotoUri(any(), any(), any()) } just runs
    }

    private fun photoLog(id: Long, uri: String, at: Long, type: CareType = CareType.PHOTO) =
        CareLog(id = id, plantId = 1L, careType = type, loggedAt = at, photoUri = uri)

    private fun logPhoto(log: CareLog) = GalleryPhoto(
        uri = log.photoUri!!,
        timestamp = log.loggedAt,
        source = GalleryPhotoSource.FromCareLog(log.id)
    )

    @Test
    fun `deleting cover plant photo falls back to a newer PHOTO-log photo over an older plant photo`() = runTest {
        val deleted = PlantPhoto(id = 1L, plantId = 1L, uri = "file:///cover.jpg", capturedAt = 3000L)
        val older = PlantPhoto(id = 2L, plantId = 1L, uri = "file:///old.jpg", capturedAt = 100L)
        val newer = photoLog(7L, "file:///log.jpg", 2000L)
        setup("file:///cover.jpg", listOf(deleted, older), listOf(newer))
        val vm = makeVm()

        vm.deletePhoto(GalleryPhoto(deleted.uri, deleted.capturedAt, GalleryPhotoSource.FromPlant(deleted)))

        coVerify { plantRepo.updateCoverPhotoUri(1L, "file:///log.jpg", any()) }
    }

    @Test
    fun `deleting a PHOTO-log cover falls back to the newest remaining PHOTO-log photo`() = runTest {
        val cover = photoLog(5L, "file:///cover.jpg", 3000L)
        val other = photoLog(6L, "file:///other.jpg", 1000L)
        setup("file:///cover.jpg", logs = listOf(cover, other))
        val vm = makeVm()

        vm.deletePhoto(logPhoto(cover))

        coVerify { careLogRepo.updateLog(match { it.id == 5L && it.photoUri == null }) }
        coVerify { plantRepo.updateCoverPhotoUri(1L, "file:///other.jpg", any()) }
    }

    @Test
    fun `deleting the cover never promotes a non-PHOTO log photo`() = runTest {
        val cover = photoLog(5L, "file:///cover.jpg", 3000L)
        val water = photoLog(6L, "file:///water.jpg", 2000L, CareType.WATER)
        setup("file:///cover.jpg", logs = listOf(cover, water))
        val vm = makeVm()

        vm.deletePhoto(logPhoto(cover))

        coVerify { plantRepo.updateCoverPhotoUri(1L, null, any()) }
    }

    @Test
    fun `deleting the last eligible photo clears the cover`() = runTest {
        val cover = photoLog(5L, "file:///cover.jpg", 3000L)
        setup("file:///cover.jpg", logs = listOf(cover))
        val vm = makeVm()

        vm.deletePhoto(logPhoto(cover))

        coVerify { plantRepo.updateCoverPhotoUri(1L, null, any()) }
    }

    @Test
    fun `deleting a non-cover photo leaves the cover unchanged`() = runTest {
        val cover = PlantPhoto(id = 1L, plantId = 1L, uri = "file:///cover.jpg", capturedAt = 3000L)
        val other = photoLog(6L, "file:///other.jpg", 1000L)
        setup("file:///cover.jpg", listOf(cover), listOf(other))
        val vm = makeVm()

        vm.deletePhoto(logPhoto(other))

        coVerify(exactly = 0) { plantRepo.updateCoverPhotoUri(any(), any(), any()) }
    }

    @Test
    fun `deleting a legacy non-PHOTO log photo that is the cover falls back`() = runTest {
        val water = photoLog(6L, "file:///water.jpg", 2000L, CareType.WATER)
        val plantPhoto = PlantPhoto(id = 2L, plantId = 1L, uri = "file:///p.jpg", capturedAt = 100L)
        setup("file:///water.jpg", listOf(plantPhoto), listOf(water))
        val vm = makeVm()

        vm.deletePhoto(logPhoto(water))

        coVerify { plantRepo.updateCoverPhotoUri(1L, "file:///p.jpg", any()) }
    }

    @Test
    fun `deleting a PHOTO care-log entry that is the cover falls back`() = runTest {
        val cover = photoLog(5L, "file:///cover.jpg", 3000L)
        val other = photoLog(6L, "file:///other.jpg", 1000L)
        setup("file:///cover.jpg", logs = listOf(cover, other))
        val vm = makeVm()

        vm.deleteLog(cover)

        coVerify { careLogRepo.deleteLog(cover) }
        coVerify { plantRepo.updateCoverPhotoUri(1L, "file:///other.jpg", any()) }
    }
}
