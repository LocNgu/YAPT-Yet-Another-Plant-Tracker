package com.yapt.planttracker.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.domain.model.LightLevel
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PotMaterial
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlantRepositoryGrowingConditionsTest {

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

    private fun plant() = Plant(name = "Fern", createdAt = 1L, updatedAt = 1L)

    @Test
    fun `growing conditions round-trip and default to unset`() = runTest {
        val bare = repo.addPlant(plant())
        val full = repo.addPlant(
            plant().copy(lightLevel = LightLevel.DIRECT, potMaterial = PotMaterial.CERAMIC, potDiameterCm = 18)
        )

        val bareRead = repo.getPlantById(bare).first()!!
        assertNull(bareRead.lightLevel)
        assertNull(bareRead.potMaterial)
        assertNull(bareRead.potDiameterCm)
        val fullRead = repo.getPlantById(full).first()!!
        assertEquals(LightLevel.DIRECT, fullRead.lightLevel)
        assertEquals(PotMaterial.CERAMIC, fullRead.potMaterial)
        assertEquals(18, fullRead.potDiameterCm)
    }

    @Test
    fun `an unknown stored enum name reads as unset instead of crashing`() = runTest {
        val id = repo.addPlant(plant().copy(potDiameterCm = 10))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE plants SET lightLevel = 'SUPERNOVA', potMaterial = 'GOLD' WHERE id = $id"
        )

        val read = repo.getPlantById(id).first()!!

        assertNull(read.lightLevel)
        assertNull(read.potMaterial)
        assertEquals(10, read.potDiameterCm)
    }

    @Test
    fun `updatePot writes only the pot columns`() = runTest {
        val id = repo.addPlant(
            plant().copy(name = "Keep", lightLevel = LightLevel.LOW, wateringIntervalDays = 9)
        )

        repo.updatePot(id, PotMaterial.TERRACOTTA, 22, updatedAt = 99L)

        val read = repo.getPlantById(id).first()!!
        assertEquals(PotMaterial.TERRACOTTA, read.potMaterial)
        assertEquals(22, read.potDiameterCm)
        assertEquals(99L, read.updatedAt)
        assertEquals("Keep", read.name)
        assertEquals(LightLevel.LOW, read.lightLevel)
        assertEquals(9, read.wateringIntervalDays)
    }
}
