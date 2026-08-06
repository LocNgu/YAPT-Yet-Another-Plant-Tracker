package com.yapt.planttracker.domain.devmode

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [DemoDataSeeder] against a real in-memory Room database rather than mocking
 * `withTransaction` — mirrors `QuickLogUseCaseBulkLogTest`'s approach for the same reason (#448).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DemoDataSeederTest {

    private lateinit var db: PlantDatabase
    private lateinit var plantRepository: PlantRepository
    private lateinit var seeder: DemoDataSeeder

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlantDatabase::class.java
        ).allowMainThreadQueries().build()
        plantRepository = PlantRepository(db.plantDao())
        seeder = DemoDataSeeder(plantRepository, CareLogRepository(db.careLogDao()), db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `seed inserts the 8-plant demo dataset with care logs`() = runTest {
        val inserted = seeder.seed()

        assertEquals(8, inserted)
        val plants = db.plantDao().getAllPlants().first()
        assertEquals(8, plants.size)
        assertTrue(plants.all { it.name.startsWith(DemoData.NAME_PREFIX) })
        val totalLogs = db.careLogDao().getAllLogs().first().size
        assertTrue(totalLogs > 0)
    }

    @Test
    fun `re-seeding replaces the demo set instead of stacking duplicates`() = runTest {
        seeder.seed()
        seeder.seed()

        val plants = db.plantDao().getAllPlants().first()
        assertEquals(8, plants.size)
    }

    @Test
    fun `seed never touches a plant without the Demo prefix`() = runTest {
        val realPlantId = plantRepository.addPlant(Plant(name = "My Real Fern", createdAt = 0L, updatedAt = 0L))

        seeder.seed()

        val realPlant = db.plantDao().getPlantById(realPlantId).first()
        assertEquals("My Real Fern", realPlant?.name)
        val allPlants = db.plantDao().getAllPlants().first()
        assertEquals(9, allPlants.size)
    }

    @Test
    fun `remove deletes every demo plant including archived ones and cascades care logs`() = runTest {
        seeder.seed()
        val demoPlants = db.plantDao().getAllPlants().first()
        db.plantDao().archivePlant(demoPlants.first().id, timestamp = 1234L)

        val removed = seeder.remove()

        assertEquals(8, removed)
        assertEquals(0, db.plantDao().getAllPlants().first().size)
        assertEquals(0, db.plantDao().getArchivedPlants().first().size)
        assertEquals(0, db.careLogDao().getAllLogs().first().size)
    }

    @Test
    fun `remove leaves non-demo plants untouched`() = runTest {
        val realPlantId = plantRepository.addPlant(Plant(name = "My Real Fern", createdAt = 0L, updatedAt = 0L))
        seeder.seed()

        seeder.remove()

        val remaining = db.plantDao().getAllPlants().first()
        assertEquals(1, remaining.size)
        assertEquals(realPlantId, remaining.single().id)
    }

    @Test
    fun `remove is a no-op when there are no demo plants`() = runTest {
        val removed = seeder.remove()

        assertEquals(0, removed)
    }
}
