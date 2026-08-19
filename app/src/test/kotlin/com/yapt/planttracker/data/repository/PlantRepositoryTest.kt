package com.yapt.planttracker.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.domain.model.Plant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlantRepositoryTest {

    private lateinit var db: PlantDatabase
    private lateinit var repo: PlantRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = PlantRepository(db.plantDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun samplePlant(
        name: String = "Monstera",
        species: String? = "Monstera deliciosa",
        room: String? = "Living Room",
        wateringIntervalDays: Int? = 7,
        fertilizingIntervalDays: Int? = 30,
        coverPhotoUri: String? = "content://photo/1",
        notes: String? = "Loves indirect light"
    ) = Plant(
        name = name,
        species = species,
        room = room,
        coverPhotoUri = coverPhotoUri,
        notes = notes,
        wateringIntervalDays = wateringIntervalDays,
        fertilizingIntervalDays = fertilizingIntervalDays,
        createdAt = 1_000_000L,
        updatedAt = 2_000_000L
    )

    // -----------------------------------------------------------------------
    // CRUD via repository
    // -----------------------------------------------------------------------

    @Test
    fun `addPlant returns auto-generated id and getAllPlants emits it`() = runTest {
        val id = repo.addPlant(samplePlant())

        repo.getAllPlants().test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(id, list[0].id)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getPlantById returns matching plant`() = runTest {
        val id = repo.addPlant(samplePlant(name = "Cactus"))

        repo.getPlantById(id).test {
            val plant = awaitItem()
            assertNotNull(plant)
            assertEquals("Cactus", plant?.name)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getPlantById emits null for unknown id`() = runTest {
        repo.getPlantById(999L).test {
            assertNull(awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `updatePlant reflects new values`() = runTest {
        val id = repo.addPlant(samplePlant(name = "Basil", wateringIntervalDays = 3))
        val updated = samplePlant(name = "Sweet Basil", wateringIntervalDays = 2).copy(id = id)
        repo.updatePlant(updated)

        repo.getPlantById(id).test {
            val plant = awaitItem()
            assertEquals("Sweet Basil", plant?.name)
            assertEquals(2, plant?.wateringIntervalDays)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `deletePlant removes it from getAllPlants`() = runTest {
        val id = repo.addPlant(samplePlant(name = "Orchid"))
        val plant = samplePlant(name = "Orchid").copy(id = id)
        repo.deletePlant(plant)

        repo.getAllPlants().test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getAllRooms returns distinct rooms in alphabetical order`() = runTest {
        repo.addPlant(samplePlant(name = "A", room = "Study"))
        repo.addPlant(samplePlant(name = "B", room = "Bedroom"))
        repo.addPlant(samplePlant(name = "C", room = "Study"))
        repo.addPlant(samplePlant(name = "D", room = null))

        repo.getAllRooms().test {
            assertEquals(listOf("Bedroom", "Study"), awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Entity <-> domain round-trip (all fields preserved)
    // -----------------------------------------------------------------------

    @Test
    fun `Plant entity-domain round-trip preserves all fields`() = runTest {
        val original = samplePlant()
        val id = repo.addPlant(original)

        repo.getPlantById(id).test {
            val roundTripped = awaitItem()
            assertNotNull(roundTripped)
            assertEquals(id, roundTripped!!.id)
            assertEquals(original.name, roundTripped.name)
            assertEquals(original.species, roundTripped.species)
            assertEquals(original.room, roundTripped.room)
            assertEquals(original.coverPhotoUri, roundTripped.coverPhotoUri)
            assertEquals(original.notes, roundTripped.notes)
            assertEquals(original.wateringIntervalDays, roundTripped.wateringIntervalDays)
            assertEquals(original.fertilizingIntervalDays, roundTripped.fertilizingIntervalDays)
            assertEquals(original.createdAt, roundTripped.createdAt)
            assertEquals(original.updatedAt, roundTripped.updatedAt)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `Plant with all nullable fields null round-trips correctly`() = runTest {
        val original = samplePlant(
            species = null,
            room = null,
            coverPhotoUri = null,
            notes = null,
            wateringIntervalDays = null,
            fertilizingIntervalDays = null
        )
        val id = repo.addPlant(original)

        repo.getPlantById(id).test {
            val roundTripped = awaitItem()
            assertNotNull(roundTripped)
            assertNull(roundTripped?.species)
            assertNull(roundTripped?.room)
            assertNull(roundTripped?.coverPhotoUri)
            assertNull(roundTripped?.notes)
            assertNull(roundTripped?.wateringIntervalDays)
            assertNull(roundTripped?.fertilizingIntervalDays)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `wateringConfidence defaults to null and round-trips a stored value`() = runTest {
        val id = repo.addPlant(samplePlant())
        repo.getPlantById(id).test {
            assertNull(awaitItem()?.wateringConfidence)
            cancelAndConsumeRemainingEvents()
        }

        val updated = samplePlant().copy(id = id, wateringConfidence = 3)
        repo.updatePlant(updated)
        repo.getPlantById(id).test {
            assertEquals(3, awaitItem()?.wateringConfidence)
            cancelAndConsumeRemainingEvents()
        }
    }
}
