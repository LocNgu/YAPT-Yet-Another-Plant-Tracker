package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [QuickLogUseCase.saveReminderPhoto] is the one photo-reminder save shared by Plant List, Calendar,
 * and Today (#836). Runs against a real in-memory Room database because the all-or-nothing contract
 * is only verifiable through the real `withTransaction` path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLogUseCaseSaveReminderPhotoTest {

    private lateinit var db: PlantDatabase
    private lateinit var plantRepo: PlantRepository
    private lateinit var careLogRepo: CareLogRepository
    private lateinit var photoRepo: PlantPhotoRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlantDatabase::class.java
        ).allowMainThreadQueries().build()
        plantRepo = PlantRepository(db.plantDao())
        careLogRepo = CareLogRepository(db.careLogDao())
        photoRepo = PlantPhotoRepository(db.plantPhotoDao())
    }

    @After
    fun tearDown() = db.close()

    private fun useCase(plants: PlantRepository = plantRepo): QuickLogUseCase {
        val application: Application = mockk(relaxed = true)
        val dataStore: DataStore<Preferences> = mockk { every { data } returns flowOf(emptyPreferences()) }
        return QuickLogUseCase(
            application,
            plants,
            careLogRepo,
            photoRepo,
            dataStore,
            db,
            WateringAdjustmentRepository(db.wateringAdjustmentDao()),
            nowProvider = { NOW }
        )
    }

    @Test
    fun `saveReminderPhoto writes photo row, PHOTO log, and cover photo together`() = runTest {
        val id = plantRepo.addPlant(Plant(name = "Fern", createdAt = 0L, updatedAt = 0L))

        val saved = useCase().saveReminderPhoto(id, "content://reminder.jpg")

        assertEquals("content://reminder.jpg", saved?.coverPhotoUri)
        val photos = photoRepo.getPhotosForPlantOnce(id)
        assertEquals(listOf("content://reminder.jpg"), photos.map { it.uri })
        assertEquals(NOW, photos.single().capturedAt)
        val logs = db.careLogDao().getLogsForPlant(id).first()
        assertEquals(1, logs.size)
        assertEquals(CareType.PHOTO.name, logs.single().careType)
        assertEquals("content://reminder.jpg", logs.single().photoUri)
        assertEquals(NOW, logs.single().loggedAt)
        val plant = plantRepo.getPlantById(id).first()
        assertEquals("content://reminder.jpg", plant?.coverPhotoUri)
        assertEquals(NOW, plant?.updatedAt)
    }

    @Test
    fun `saveReminderPhoto returns null and writes nothing for a missing plant`() = runTest {
        val saved = useCase().saveReminderPhoto(999L, "content://reminder.jpg")

        assertNull(saved)
        assertTrue(db.plantPhotoDao().getAllPhotos().first().isEmpty())
        assertTrue(db.careLogDao().getAllLogs().first().isEmpty())
    }

    @Test
    fun `saveReminderPhoto rolls back every write when the cover update fails`() = runTest {
        val id = plantRepo.addPlant(Plant(name = "Fern", createdAt = 0L, updatedAt = 0L))
        val failingPlants = spyk(plantRepo)
        coEvery { failingPlants.updateCoverPhotoUri(any(), any(), any()) } throws IllegalStateException("boom")

        try {
            useCase(failingPlants).saveReminderPhoto(id, "content://reminder.jpg")
            fail("expected the cover update failure to propagate")
        } catch (_: IllegalStateException) {
            // expected
        }

        assertTrue(db.plantPhotoDao().getAllPhotos().first().isEmpty())
        assertTrue(db.careLogDao().getAllLogs().first().isEmpty())
        assertNull(plantRepo.getPlantById(id).first()?.coverPhotoUri)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
