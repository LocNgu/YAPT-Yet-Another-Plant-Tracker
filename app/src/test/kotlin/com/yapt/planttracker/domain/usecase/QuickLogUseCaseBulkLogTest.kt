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
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
 * Exercises [QuickLogUseCase.bulkLog] against a real in-memory Room database rather than mocking
 * the `withTransaction` extension — the transaction path can only be verified end-to-end (#448).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLogUseCaseBulkLogTest {

    private lateinit var db: PlantDatabase
    private lateinit var plantRepo: PlantRepository
    private lateinit var careLogRepo: CareLogRepository
    private lateinit var useCase: QuickLogUseCase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlantDatabase::class.java
        ).allowMainThreadQueries().build()
        plantRepo = PlantRepository(db.plantDao())
        careLogRepo = CareLogRepository(db.careLogDao())
        val application: Application = mockk(relaxed = true)
        val dataStore: DataStore<Preferences> = mockk { every { data } returns flowOf(emptyPreferences()) }
        useCase = QuickLogUseCase(
            application,
            plantRepo,
            careLogRepo,
            PlantPhotoRepository(db.plantPhotoDao()),
            dataStore,
            db,
            WateringAdjustmentRepository(db.wateringAdjustmentDao())
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `bulkLog water writes one WATER log for every plant`() = runTest {
        val id1 = plantRepo.addPlant(Plant(name = "A", createdAt = 0L, updatedAt = 0L))
        val id2 = plantRepo.addPlant(Plant(name = "B", createdAt = 0L, updatedAt = 0L))

        useCase.bulkLog(
            listOf(
                Plant(id = id1, name = "A", createdAt = 0L, updatedAt = 0L),
                Plant(id = id2, name = "B", createdAt = 0L, updatedAt = 0L)
            ),
            CareType.WATER
        )

        val logs = db.careLogDao().getAllLogs().first()
        assertEquals(2, logs.size)
        assertEquals(setOf(id1, id2), logs.map { it.plantId }.toSet())
        assertTrue(logs.all { it.careType == CareType.WATER.name })
    }

    @Test
    fun `bulkLog liquid-fertilizer writes paired FERTILIZE and WATER logs for every plant`() = runTest {
        val id1 = plantRepo.addPlant(Plant(name = "A", useLiquidFertilizer = true, createdAt = 0L, updatedAt = 0L))
        val id2 = plantRepo.addPlant(Plant(name = "B", useLiquidFertilizer = true, createdAt = 0L, updatedAt = 0L))

        useCase.bulkLog(
            listOf(
                Plant(id = id1, name = "A", useLiquidFertilizer = true, createdAt = 0L, updatedAt = 0L),
                Plant(id = id2, name = "B", useLiquidFertilizer = true, createdAt = 0L, updatedAt = 0L)
            ),
            CareType.FERTILIZE
        )

        val logs = db.careLogDao().getAllLogs().first()
        // Each liquid-fertilizer plant gets a paired FERTILIZE + WATER entry.
        assertEquals(4, logs.size)
        assertEquals(2, logs.count { it.careType == CareType.FERTILIZE.name })
        assertEquals(2, logs.count { it.careType == CareType.WATER.name })
    }

    @Test
    fun `bulkLog water skips a plant already watered today and logs the rest`() = runTest {
        val id1 = plantRepo.addPlant(Plant(name = "A", createdAt = 0L, updatedAt = 0L))
        val id2 = plantRepo.addPlant(Plant(name = "B", createdAt = 0L, updatedAt = 0L))
        careLogRepo.addLog(CareLog(plantId = id1, careType = CareType.WATER, loggedAt = System.currentTimeMillis()))

        val result = useCase.bulkLog(
            listOf(
                Plant(id = id1, name = "A", createdAt = 0L, updatedAt = 0L),
                Plant(id = id2, name = "B", createdAt = 0L, updatedAt = 0L)
            ),
            CareType.WATER
        )

        assertEquals(1, result.loggedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(2, result.totalCount)
        val logsForId1 = db.careLogDao().getLogsForPlant(id1).first()
        assertEquals(1, logsForId1.size) // no second WATER log was inserted
        val logsForId2 = db.careLogDao().getLogsForPlant(id2).first()
        assertEquals(1, logsForId2.size)
    }
}
