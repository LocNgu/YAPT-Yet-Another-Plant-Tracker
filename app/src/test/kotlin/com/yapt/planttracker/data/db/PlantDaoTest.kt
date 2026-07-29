package com.yapt.planttracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.yapt.planttracker.data.entity.PlantEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlantDaoTest {

    private lateinit var db: PlantDatabase
    private lateinit var dao: PlantDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.plantDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun plant(
        name: String = "Fern",
        species: String? = null,
        room: String? = null,
        wateringIntervalDays: Int? = 7,
        fertilizingIntervalDays: Int? = null
    ) = PlantEntity(
        name = name,
        species = species,
        room = room,
        coverPhotoUri = null,
        notes = null,
        wateringIntervalDays = wateringIntervalDays,
        fertilizingIntervalDays = fertilizingIntervalDays,
        createdAt = 1_000_000L,
        updatedAt = 1_000_000L
    )

    @Test
    fun `insertPlant and getAllPlants returns inserted plant`() = runTest {
        dao.insertPlant(plant(name = "Fern"))

        dao.getAllPlants().test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals("Fern", list[0].name)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getAllPlants returns plants ordered by name ascending`() = runTest {
        dao.insertPlant(plant(name = "Zebra Plant"))
        dao.insertPlant(plant(name = "Aloe"))
        dao.insertPlant(plant(name = "Monstera"))

        dao.getAllPlants().test {
            val list = awaitItem()
            assertEquals(listOf("Aloe", "Monstera", "Zebra Plant"), list.map { it.name })
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getPlantById returns correct plant`() = runTest {
        val id = dao.insertPlant(plant(name = "Cactus", species = "Opuntia"))

        dao.getPlantById(id).test {
            val found = awaitItem()
            assertEquals("Cactus", found?.name)
            assertEquals("Opuntia", found?.species)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getPlantById returns null for unknown id`() = runTest {
        dao.getPlantById(999L).test {
            assertNull(awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `updatePlant changes persisted fields`() = runTest {
        val id = dao.insertPlant(plant(name = "Basil", wateringIntervalDays = 3))
        val updated = plant(name = "Sweet Basil", wateringIntervalDays = 2).copy(id = id)
        dao.updatePlant(updated)

        dao.getPlantById(id).test {
            val found = awaitItem()
            assertEquals("Sweet Basil", found?.name)
            assertEquals(2, found?.wateringIntervalDays)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `deletePlant removes plant from list`() = runTest {
        val id = dao.insertPlant(plant(name = "Orchid"))
        val entity = plant(name = "Orchid").copy(id = id)
        dao.deletePlant(entity)

        dao.getAllPlants().test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getAllRooms returns distinct non-null rooms ordered`() = runTest {
        dao.insertPlant(plant(name = "A", room = "Living Room"))
        dao.insertPlant(plant(name = "B", room = "Bedroom"))
        dao.insertPlant(plant(name = "C", room = "Living Room"))
        dao.insertPlant(plant(name = "D", room = null))

        dao.getAllRooms().test {
            val rooms = awaitItem()
            assertEquals(listOf("Bedroom", "Living Room"), rooms)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getAllPlants Flow emits update when plant is inserted`() = runTest {
        dao.getAllPlants().test {
            // initial empty emission
            assertEquals(0, awaitItem().size)

            dao.insertPlant(plant(name = "Peace Lily"))

            // updated emission after insert
            assertEquals(1, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `deleteAll removes every plant`() = runTest {
        dao.insertPlant(plant(name = "A"))
        dao.insertPlant(plant(name = "B"))
        dao.deleteAll()

        dao.getAllPlants().test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `insertAll persists all provided plants`() = runTest {
        val plants = listOf(
            plant(name = "Rose"),
            plant(name = "Tulip"),
            plant(name = "Daisy")
        )
        val ids = dao.insertAll(plants)
        assertEquals(3, ids.size)

        dao.getAllPlants().test {
            assertEquals(3, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `archivePlant excludes plant from getAllPlants`() = runTest {
        val id = dao.insertPlant(plant(name = "Monstera"))
        dao.archivePlant(id, timestamp = 5000L)

        dao.getAllPlants().test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `archivePlant appears in getArchivedPlants`() = runTest {
        val id = dao.insertPlant(plant(name = "Fern"))
        dao.archivePlant(id, timestamp = 6000L)

        dao.getArchivedPlants().test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals("Fern", list[0].name)
            assertEquals(6000L, list[0].archivedAt)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `archivePlants archives every id in the list and leaves others active`() = runTest {
        val id1 = dao.insertPlant(plant(name = "One"))
        val id2 = dao.insertPlant(plant(name = "Two"))
        val id3 = dao.insertPlant(plant(name = "Three"))

        dao.archivePlants(listOf(id1, id3), timestamp = 4200L)

        dao.getAllPlants().test {
            val active = awaitItem()
            assertEquals(1, active.size)
            assertEquals(id2, active[0].id)
            cancelAndConsumeRemainingEvents()
        }
        dao.getArchivedPlants().test {
            val archived = awaitItem()
            assertEquals(setOf(id1, id3), archived.map { it.id }.toSet())
            assertTrue(archived.all { it.archivedAt == 4200L })
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `restorePlants restores every id in the list`() = runTest {
        val id1 = dao.insertPlant(plant(name = "One"))
        val id2 = dao.insertPlant(plant(name = "Two"))
        dao.archivePlants(listOf(id1, id2), timestamp = 4300L)

        dao.restorePlants(listOf(id1, id2))

        dao.getAllPlants().test {
            assertEquals(2, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
        dao.getArchivedPlants().test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `restorePlant reappears in getAllPlants`() = runTest {
        val id = dao.insertPlant(plant(name = "Cactus"))
        dao.archivePlant(id, timestamp = 7000L)
        dao.restorePlant(id)

        dao.getAllPlants().test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals("Cactus", list[0].name)
            assertNull(list[0].archivedAt)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getArchivedCount increments on archive`() = runTest {
        val id1 = dao.insertPlant(plant(name = "Rose"))
        val id2 = dao.insertPlant(plant(name = "Tulip"))

        dao.getArchivedCount().test {
            assertEquals(0, awaitItem())
            dao.archivePlant(id1, timestamp = 1L)
            assertEquals(1, awaitItem())
            dao.archivePlant(id2, timestamp = 2L)
            assertEquals(2, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `deleteAllArchived keeps active plants`() = runTest {
        val id1 = dao.insertPlant(plant(name = "Active"))
        val id2 = dao.insertPlant(plant(name = "Archived"))
        dao.archivePlant(id2, timestamp = 8000L)
        dao.deleteAllArchived()

        dao.getAllPlants().test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals("Active", list[0].name)
            cancelAndConsumeRemainingEvents()
        }

        dao.getArchivedPlants().test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getAllRooms excludes archived plants rooms`() = runTest {
        dao.insertPlant(plant(name = "A", room = "Living Room"))
        val id = dao.insertPlant(plant(name = "B", room = "Bedroom"))
        dao.archivePlant(id, timestamp = 9000L)

        dao.getAllRooms().test {
            val rooms = awaitItem()
            assertEquals(listOf("Living Room"), rooms)
            cancelAndConsumeRemainingEvents()
        }
    }
}
