package com.yapt.planttracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.data.entity.PlantPhotoEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlantPhotoDaoTest {

    private lateinit var db: PlantDatabase
    private lateinit var dao: PlantPhotoDao
    private lateinit var plantDao: PlantDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.plantPhotoDao()
        plantDao = db.plantDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertPlant(name: String = "Fern"): Long =
        plantDao.insertPlant(
            PlantEntity(
                name = name, species = null, room = null, coverPhotoUri = null,
                notes = null, wateringIntervalDays = 7, fertilizingIntervalDays = null,
                createdAt = 1_000_000L, updatedAt = 1_000_000L
            )
        )

    @Test
    fun `insertPhoto and getPhotosForPlant returns inserted photo`() = runTest {
        val plantId = insertPlant()
        dao.insertPhoto(PlantPhotoEntity(plantId = plantId, uri = "content://photo1", capturedAt = 1000L))

        dao.getPhotosForPlant(plantId).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals("content://photo1", list[0].uri)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getPhotosForPlant returns photos sorted by capturedAt descending`() = runTest {
        val plantId = insertPlant()
        dao.insertPhoto(PlantPhotoEntity(plantId = plantId, uri = "content://photo_oldest", capturedAt = 1000L))
        dao.insertPhoto(PlantPhotoEntity(plantId = plantId, uri = "content://photo_newest", capturedAt = 3000L))
        dao.insertPhoto(PlantPhotoEntity(plantId = plantId, uri = "content://photo_middle", capturedAt = 2000L))

        dao.getPhotosForPlant(plantId).test {
            val list = awaitItem()
            assertEquals(3, list.size)
            assertEquals("content://photo_newest", list[0].uri)
            assertEquals("content://photo_middle", list[1].uri)
            assertEquals("content://photo_oldest", list[2].uri)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getPhotosForPlant returns only photos for requested plant`() = runTest {
        val plantId1 = insertPlant("Fern")
        val plantId2 = insertPlant("Cactus")
        dao.insertPhoto(PlantPhotoEntity(plantId = plantId1, uri = "content://fern_photo", capturedAt = 1000L))
        dao.insertPhoto(PlantPhotoEntity(plantId = plantId2, uri = "content://cactus_photo", capturedAt = 2000L))

        dao.getPhotosForPlant(plantId1).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals("content://fern_photo", list[0].uri)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `deletePhoto removes the photo`() = runTest {
        val plantId = insertPlant()
        val photoId = dao.insertPhoto(PlantPhotoEntity(plantId = plantId, uri = "content://photo1", capturedAt = 1000L))

        dao.deletePhoto(PlantPhotoEntity(id = photoId, plantId = plantId, uri = "content://photo1", capturedAt = 1000L))

        dao.getPhotosForPlant(plantId).test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `deleteAll clears all plant photos`() = runTest {
        val plantId = insertPlant()
        dao.insertPhoto(PlantPhotoEntity(plantId = plantId, uri = "content://photo1", capturedAt = 1000L))
        dao.insertPhoto(PlantPhotoEntity(plantId = plantId, uri = "content://photo2", capturedAt = 2000L))

        dao.deleteAll()

        assertEquals(0, dao.getAllPhotos().first().size)
    }

    @Test
    fun `deleting plant cascades to plant_photos`() = runTest {
        val plantId = insertPlant()
        dao.insertPhoto(PlantPhotoEntity(plantId = plantId, uri = "content://photo1", capturedAt = 1000L))

        val plant = plantDao.getPlantById(plantId).first()!!
        plantDao.deletePlant(plant)

        assertEquals(0, dao.getAllPhotos().first().size)
    }

    @Test
    fun `insertAll persists all provided photos`() = runTest {
        val plantId = insertPlant()
        val photos = listOf(
            PlantPhotoEntity(plantId = plantId, uri = "content://a", capturedAt = 1000L),
            PlantPhotoEntity(plantId = plantId, uri = "content://b", capturedAt = 2000L),
            PlantPhotoEntity(plantId = plantId, uri = "content://c", capturedAt = 3000L)
        )
        dao.insertAll(photos)

        assertEquals(3, dao.getAllPhotos().first().size)
    }
}
