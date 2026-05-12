package com.yapt.planttracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.yapt.planttracker.data.entity.CareLogEntity
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.WateringFeedback
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
class CareLogDaoTest {

    private lateinit var db: PlantDatabase
    private lateinit var plantDao: PlantDao
    private lateinit var careLogDao: CareLogDao

    private var testPlantId: Long = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        plantDao = db.plantDao()
        careLogDao = db.careLogDao()

        // Insert a parent plant so FK constraints are satisfied
        testPlantId = 0L // will be set in runTest; use blocking approach via allowMainThreadQueries
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Create a parent plant synchronously (allowMainThreadQueries is set). */
    private suspend fun insertParentPlant(name: String = "TestPlant"): Long =
        plantDao.insertPlant(
            PlantEntity(
                name = name,
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

    private fun log(
        plantId: Long,
        careType: String = CareType.WATER.name,
        loggedAt: Long = System.currentTimeMillis(),
        notes: String? = null,
        photoUri: String? = null,
        wateringFeedback: String? = null
    ) = CareLogEntity(
        plantId = plantId,
        careType = careType,
        loggedAt = loggedAt,
        notes = notes,
        photoUri = photoUri,
        amount = null,
        wateringFeedback = wateringFeedback
    )

    @Test
    fun `insertLog and getLogsForPlant returns inserted log`() = runTest {
        val plantId = insertParentPlant()
        careLogDao.insertLog(log(plantId, careType = CareType.WATER.name))

        careLogDao.getLogsForPlant(plantId).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(CareType.WATER.name, list[0].careType)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getLogsForPlant returns logs ordered by loggedAt descending`() = runTest {
        val plantId = insertParentPlant()
        careLogDao.insertLog(log(plantId, loggedAt = 100L))
        careLogDao.insertLog(log(plantId, loggedAt = 300L))
        careLogDao.insertLog(log(plantId, loggedAt = 200L))

        careLogDao.getLogsForPlant(plantId).test {
            val list = awaitItem()
            assertEquals(listOf(300L, 200L, 100L), list.map { it.loggedAt })
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getLastLogOfType returns most recent WATER log`() = runTest {
        val plantId = insertParentPlant()
        careLogDao.insertLog(log(plantId, careType = CareType.WATER.name, loggedAt = 100L))
        careLogDao.insertLog(log(plantId, careType = CareType.WATER.name, loggedAt = 500L))
        careLogDao.insertLog(log(plantId, careType = CareType.FERTILIZE.name, loggedAt = 900L))

        val latest = careLogDao.getLastLogOfType(plantId, CareType.WATER.name)
        assertNotNull(latest)
        assertEquals(500L, latest?.loggedAt)
        assertEquals(CareType.WATER.name, latest?.careType)
    }

    @Test
    fun `getLastLogOfType returns most recent FERTILIZE log`() = runTest {
        val plantId = insertParentPlant()
        careLogDao.insertLog(log(plantId, careType = CareType.FERTILIZE.name, loggedAt = 200L))
        careLogDao.insertLog(log(plantId, careType = CareType.FERTILIZE.name, loggedAt = 800L))
        careLogDao.insertLog(log(plantId, careType = CareType.WATER.name, loggedAt = 999L))

        val latest = careLogDao.getLastLogOfType(plantId, CareType.FERTILIZE.name)
        assertNotNull(latest)
        assertEquals(800L, latest?.loggedAt)
    }

    @Test
    fun `getLastLogOfType returns null when no log of that type exists`() = runTest {
        val plantId = insertParentPlant()
        careLogDao.insertLog(log(plantId, careType = CareType.WATER.name))

        val result = careLogDao.getLastLogOfType(plantId, CareType.FERTILIZE.name)
        assertNull(result)
    }

    @Test
    fun `getLastTwoLogsOfType returns at most two most recent logs`() = runTest {
        val plantId = insertParentPlant()
        careLogDao.insertLog(log(plantId, careType = CareType.WATER.name, loggedAt = 100L))
        careLogDao.insertLog(log(plantId, careType = CareType.WATER.name, loggedAt = 200L))
        careLogDao.insertLog(log(plantId, careType = CareType.WATER.name, loggedAt = 300L))

        val result = careLogDao.getLastTwoLogsOfType(plantId, CareType.WATER.name)
        assertEquals(2, result.size)
        assertEquals(300L, result[0].loggedAt)
        assertEquals(200L, result[1].loggedAt)
    }

    @Test
    fun `deleteLog removes log from plant's logs`() = runTest {
        val plantId = insertParentPlant()
        val id = careLogDao.insertLog(log(plantId, careType = CareType.PRUNE.name))
        val entity = log(plantId, careType = CareType.PRUNE.name).copy(id = id)
        careLogDao.deleteLog(entity)

        careLogDao.getLogsForPlant(plantId).test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getCareLogCount returns correct count`() = runTest {
        val plantId = insertParentPlant()
        careLogDao.insertLog(log(plantId))
        careLogDao.insertLog(log(plantId))
        careLogDao.insertLog(log(plantId))

        assertEquals(3, careLogDao.getCareLogCount(plantId))
    }

    @Test
    fun `getPhotoLogsForPlant returns only logs with a photo URI`() = runTest {
        val plantId = insertParentPlant()
        careLogDao.insertLog(log(plantId, photoUri = "content://photo/1"))
        careLogDao.insertLog(log(plantId, photoUri = null))
        careLogDao.insertLog(log(plantId, photoUri = "content://photo/2"))

        careLogDao.getPhotoLogsForPlant(plantId).test {
            val list = awaitItem()
            assertEquals(2, list.size)
            assert(list.all { it.photoUri != null })
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getLogById returns correct log`() = runTest {
        val plantId = insertParentPlant()
        val id = careLogDao.insertLog(log(plantId, notes = "Special note"))

        val result = careLogDao.getLogById(id)
        assertNotNull(result)
        assertEquals("Special note", result?.notes)
    }

    @Test
    fun `getLogById returns null for unknown id`() = runTest {
        val result = careLogDao.getLogById(999L)
        assertNull(result)
    }

    @Test
    fun `getLogsForPlant Flow emits update when log is inserted`() = runTest {
        val plantId = insertParentPlant()

        careLogDao.getLogsForPlant(plantId).test {
            assertEquals(0, awaitItem().size)

            careLogDao.insertLog(log(plantId))

            assertEquals(1, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `wateringFeedback is persisted and retrieved correctly`() = runTest {
        val plantId = insertParentPlant()
        val id = careLogDao.insertLog(
            log(plantId, wateringFeedback = WateringFeedback.TOO_LATE.name)
        )

        val result = careLogDao.getLogById(id)
        assertEquals(WateringFeedback.TOO_LATE.name, result?.wateringFeedback)
    }

    @Test
    fun `cascading delete removes care logs when plant is deleted`() = runTest {
        val plantId = insertParentPlant()
        careLogDao.insertLog(log(plantId))
        careLogDao.insertLog(log(plantId))

        // Delete the parent plant — FK CASCADE should remove child logs
        plantDao.deleteAll()

        careLogDao.getLogsForPlant(plantId).test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `deleteAll removes all care logs`() = runTest {
        val plantId = insertParentPlant()
        careLogDao.insertLog(log(plantId))
        careLogDao.insertLog(log(plantId))
        careLogDao.deleteAll()

        assertEquals(0, careLogDao.getCareLogCount(plantId))
    }

    @Test
    fun `insertAll persists all provided logs`() = runTest {
        val plantId = insertParentPlant()
        val logs = listOf(
            log(plantId, careType = CareType.WATER.name),
            log(plantId, careType = CareType.FERTILIZE.name),
            log(plantId, careType = CareType.PRUNE.name)
        )
        val ids = careLogDao.insertAll(logs)
        assertEquals(3, ids.size)
        assertEquals(3, careLogDao.getCareLogCount(plantId))
    }
}
