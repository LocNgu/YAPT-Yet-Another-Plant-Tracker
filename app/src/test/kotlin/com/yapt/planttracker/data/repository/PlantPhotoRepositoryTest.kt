package com.yapt.planttracker.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.domain.model.PlantPhoto
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class PlantPhotoRepositoryTest {

    private lateinit var db: PlantDatabase
    private var plantId: Long = 0L

    @Before
    fun setUp() = Unit // plantId inserted inside each runTest via init()

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun init(): PlantPhotoRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        plantId = db.plantDao().insertPlant(
            PlantEntity(
                name = "TestPlant",
                species = null,
                room = null,
                coverPhotoUri = null,
                notes = null,
                wateringIntervalDays = 7,
                fertilizingIntervalDays = null,
                createdAt = 1_000_000L,
                updatedAt = 1_000_000L
            )
        )
        return PlantPhotoRepository(db.plantPhotoDao())
    }

    @Test
    fun `deletePhoto fires onPhotoReferencesRemoved`() = runTest {
        init()
        var callCount = 0
        val callbackRepo = PlantPhotoRepository(db.plantPhotoDao(), onPhotoReferencesRemoved = { callCount++ })
        val photo = PlantPhoto(plantId = plantId, uri = "content://photo/1", capturedAt = 1_000L)
        val id = callbackRepo.addPhoto(photo)

        callbackRepo.deletePhoto(photo.copy(id = id))

        assertEquals(1, callCount)
    }
}
