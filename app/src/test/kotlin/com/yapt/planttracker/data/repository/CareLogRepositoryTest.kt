package com.yapt.planttracker.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.WateringFeedback
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class CareLogRepositoryTest {

    private lateinit var db: PlantDatabase
    private lateinit var repo: CareLogRepository
    private var plantId: Long = 0L

    @Before
    fun setUp() = Unit // plantId inserted inside each runTest via insertParentPlant()

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun buildDb(): PlantDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    /** Initialises [db], [repo], and [plantId] within a single runTest block. */
    private suspend fun init(): Long {
        db = buildDb()
        repo = CareLogRepository(db.careLogDao())
        return db.plantDao().insertPlant(
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
        ).also { plantId = it }
    }

    private fun careLog(
        id: Long = 0L,
        plantId: Long,
        careType: CareType = CareType.WATER,
        loggedAt: Long = System.currentTimeMillis(),
        notes: String? = null,
        photoUri: String? = null,
        wateringFeedback: WateringFeedback? = null
    ) = CareLog(
        id = id,
        plantId = plantId,
        careType = careType,
        loggedAt = loggedAt,
        notes = notes,
        photoUri = photoUri,
        amount = null,
        wateringFeedback = wateringFeedback
    )

    // -----------------------------------------------------------------------
    // CRUD via repository
    // -----------------------------------------------------------------------

    @Test
    fun `addLog returns id and getLogsForPlant emits it`() = runTest {
        init()
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER))

        repo.getLogsForPlant(plantId).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(CareType.WATER, list[0].careType)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getLastLogOfType returns most recent WATER log as domain object`() = runTest {
        init()
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER, loggedAt = 100L))
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER, loggedAt = 500L))
        repo.addLog(careLog(plantId = plantId, careType = CareType.FERTILIZE, loggedAt = 900L))

        val latest = repo.getLastLogOfType(plantId, CareType.WATER)
        assertNotNull(latest)
        assertEquals(500L, latest?.loggedAt)
        assertEquals(CareType.WATER, latest?.careType)
    }

    @Test
    fun `getLastLogOfType returns null when no log of that type exists`() = runTest {
        init()
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER))

        val result = repo.getLastLogOfType(plantId, CareType.FERTILIZE)
        assertNull(result)
    }

    @Test
    fun `getLastTwoWaterings returns two most recent water logs`() = runTest {
        init()
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER, loggedAt = 100L))
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER, loggedAt = 200L))
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER, loggedAt = 300L))

        val result = repo.getLastTwoWaterings(plantId)
        assertEquals(2, result.size)
        assertEquals(300L, result[0].loggedAt)
        assertEquals(200L, result[1].loggedAt)
    }

    @Test
    fun `getRecentWaterings returns up to limit most recent water logs newest first`() = runTest {
        init()
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER, loggedAt = 100L))
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER, loggedAt = 200L))
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER, loggedAt = 300L))
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER, loggedAt = 400L))

        val result = repo.getRecentWaterings(plantId, limit = 3)
        assertEquals(3, result.size)
        assertEquals(400L, result[0].loggedAt)
        assertEquals(300L, result[1].loggedAt)
        assertEquals(200L, result[2].loggedAt)
    }

    @Test
    fun `getRecentWaterings ignores non-WATER logs`() = runTest {
        init()
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER, loggedAt = 100L))
        repo.addLog(careLog(plantId = plantId, careType = CareType.FERTILIZE, loggedAt = 200L))

        val result = repo.getRecentWaterings(plantId, limit = 3)
        assertEquals(1, result.size)
        assertEquals(CareType.WATER, result[0].careType)
    }

    @Test
    fun `getCareLogCount returns correct count`() = runTest {
        init()
        repo.addLog(careLog(plantId = plantId))
        repo.addLog(careLog(plantId = plantId))

        assertEquals(2, repo.getCareLogCount(plantId))
    }

    @Test
    fun `getLogById returns matching domain log`() = runTest {
        init()
        val id = repo.addLog(careLog(plantId = plantId, notes = "Test note"))

        val result = repo.getLogById(id)
        assertNotNull(result)
        assertEquals("Test note", result?.notes)
        assertEquals(CareType.WATER, result?.careType)
    }

    @Test
    fun `getLogById returns null for unknown id`() = runTest {
        init()
        assertNull(repo.getLogById(999L))
    }

    @Test
    fun `deleteLog removes it from plant logs`() = runTest {
        init()
        val id = repo.addLog(careLog(plantId = plantId, careType = CareType.PRUNE))
        val toDelete = careLog(id = id, plantId = plantId, careType = CareType.PRUNE)
        repo.deleteLog(toDelete)

        repo.getLogsForPlant(plantId).test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getPhotoLogsForPlant returns only logs with photos`() = runTest {
        init()
        repo.addLog(careLog(plantId = plantId, photoUri = "content://photo/1"))
        repo.addLog(careLog(plantId = plantId, photoUri = null))

        repo.getPhotoLogsForPlant(plantId).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals("content://photo/1", list[0].photoUri)
            cancelAndConsumeRemainingEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Entity <-> domain round-trip (all fields preserved)
    // -----------------------------------------------------------------------

    @Test
    fun `CareLog entity-domain round-trip preserves all fields`() = runTest {
        init()
        val original = careLog(
            plantId = plantId,
            careType = CareType.FERTILIZE,
            loggedAt = 5_000_000L,
            notes = "Monthly feed",
            photoUri = "content://photo/42",
            wateringFeedback = null
        )
        val id = repo.addLog(original)

        val roundTripped = repo.getLogById(id)
        assertNotNull(roundTripped)
        assertEquals(id, roundTripped!!.id)
        assertEquals(plantId, roundTripped.plantId)
        assertEquals(CareType.FERTILIZE, roundTripped.careType)
        assertEquals(5_000_000L, roundTripped.loggedAt)
        assertEquals("Monthly feed", roundTripped.notes)
        assertEquals("content://photo/42", roundTripped.photoUri)
        assertNull(roundTripped.wateringFeedback)
    }

    @Test
    fun `CareLog with wateringFeedback round-trips correctly`() = runTest {
        init()
        val original = careLog(
            plantId = plantId,
            careType = CareType.WATER,
            wateringFeedback = WateringFeedback.TOO_LATE
        )
        val id = repo.addLog(original)

        val roundTripped = repo.getLogById(id)
        assertEquals(WateringFeedback.TOO_LATE, roundTripped?.wateringFeedback)
    }

    @Test
    fun `CareLog with all nullable fields null round-trips correctly`() = runTest {
        init()
        val original = careLog(
            plantId = plantId,
            notes = null,
            photoUri = null,
            wateringFeedback = null
        )
        val id = repo.addLog(original)

        val roundTripped = repo.getLogById(id)
        assertNotNull(roundTripped)
        assertNull(roundTripped?.notes)
        assertNull(roundTripped?.photoUri)
        assertNull(roundTripped?.wateringFeedback)
    }

    @Test
    fun `unknown careType string in DB falls back to NOTE`() = runTest {
        init()
        // Insert a raw row with an unrecognised care type via the DAO directly
        val rawDao = db.careLogDao()
        val rawId = rawDao.insertLog(
            com.yapt.planttracker.data.entity.CareLogEntity(
                plantId = plantId,
                careType = "DELETED_TYPE",
                loggedAt = 1L,
                notes = null,
                photoUri = null,
                amount = null,
                wateringFeedback = null
            )
        )

        val result = repo.getLogById(rawId)
        assertNotNull(result)
        assertEquals(CareType.NOTE, result?.careType)
    }

    @Test
    fun `unknown wateringFeedback string in DB falls back to null`() = runTest {
        init()
        val rawDao = db.careLogDao()
        val rawId = rawDao.insertLog(
            com.yapt.planttracker.data.entity.CareLogEntity(
                plantId = plantId,
                careType = CareType.WATER.name,
                loggedAt = 1L,
                notes = null,
                photoUri = null,
                amount = null,
                wateringFeedback = "DELETED_FEEDBACK"
            )
        )

        val result = repo.getLogById(rawId)
        assertNull(result?.wateringFeedback)
    }

    // hasLogOfTypeOnDay (#509)

    @Test
    fun `hasLogOfTypeOnDay returns true for a same-day log of the same type`() = runTest {
        init()
        val now = System.currentTimeMillis()
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER, loggedAt = now))

        assertTrue(repo.hasLogOfTypeOnDay(plantId, CareType.WATER, now))
    }

    @Test
    fun `hasLogOfTypeOnDay returns false when no log exists for that type today`() = runTest {
        init()
        val now = System.currentTimeMillis()

        assertFalse(repo.hasLogOfTypeOnDay(plantId, CareType.WATER, now))
    }

    @Test
    fun `hasLogOfTypeOnDay returns false for a log of a different careType`() = runTest {
        init()
        val now = System.currentTimeMillis()
        repo.addLog(careLog(plantId = plantId, careType = CareType.FERTILIZE, loggedAt = now))

        assertFalse(repo.hasLogOfTypeOnDay(plantId, CareType.WATER, now))
    }

    @Test
    fun `hasLogOfTypeOnDay returns false for a log on a different calendar day`() = runTest {
        init()
        val now = System.currentTimeMillis()
        val yesterday = now - 24L * 60 * 60 * 1000
        repo.addLog(careLog(plantId = plantId, careType = CareType.WATER, loggedAt = yesterday))

        assertFalse(repo.hasLogOfTypeOnDay(plantId, CareType.WATER, now))
    }

    @Test
    fun `hasLogOfTypeOnDay excludes the given log id`() = runTest {
        init()
        val now = System.currentTimeMillis()
        val id = repo.addLog(careLog(plantId = plantId, careType = CareType.WATER, loggedAt = now))

        assertFalse(repo.hasLogOfTypeOnDay(plantId, CareType.WATER, now, excludeLogId = id))
    }
}
