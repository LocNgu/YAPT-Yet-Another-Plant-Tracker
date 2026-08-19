package com.yapt.planttracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.data.entity.PlantIssueEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
class PlantIssueDaoTest {

    private lateinit var db: PlantDatabase
    private lateinit var plantDao: PlantDao
    private lateinit var plantIssueDao: PlantIssueDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        plantDao = db.plantDao()
        plantIssueDao = db.plantIssueDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertParentPlant(name: String = "TestPlant"): Long =
        plantDao.insertPlant(
            PlantEntity(
                name = name,
                species = null,
                room = null,
                coverPhotoUri = null,
                notes = null,
                wateringIntervalDays = null,
                fertilizingIntervalDays = null,
                createdAt = 1_000_000L,
                updatedAt = 1_000_000L
            )
        )

    private fun issue(
        plantId: Long,
        name: String = "Spider mites",
        startedAt: Long = 1_000_000L,
        resolvedAt: Long? = null,
        resolutionNote: String? = null
    ) = PlantIssueEntity(
        plantId = plantId,
        name = name,
        startedAt = startedAt,
        resolvedAt = resolvedAt,
        resolutionNote = resolutionNote
    )

    @Test
    fun `insertIssue and getIssuesForPlant returns inserted issue`() = runTest {
        val plantId = insertParentPlant()
        plantIssueDao.insertIssue(issue(plantId))

        plantIssueDao.getIssuesForPlant(plantId).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals("Spider mites", list[0].name)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `insertIssue persists a non-null linkedReminderId`() = runTest {
        val plantId = insertParentPlant()
        plantIssueDao.insertIssue(issue(plantId).copy(linkedReminderId = 42L))

        plantIssueDao.getIssuesForPlant(plantId).test {
            assertEquals(42L, awaitItem()[0].linkedReminderId)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getActiveIssuesForPlant excludes resolved issues`() = runTest {
        val plantId = insertParentPlant()
        plantIssueDao.insertIssue(issue(plantId, name = "Active", resolvedAt = null))
        plantIssueDao.insertIssue(issue(plantId, name = "Resolved", resolvedAt = 5000L))

        plantIssueDao.getActiveIssuesForPlant(plantId).test {
            val list = awaitItem()
            assertEquals(listOf("Active"), list.map { it.name })
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getActiveIssueCountForPlant counts only unresolved issues`() = runTest {
        val plantId = insertParentPlant()
        plantIssueDao.insertIssue(issue(plantId, name = "A", resolvedAt = null))
        plantIssueDao.insertIssue(issue(plantId, name = "B", resolvedAt = null))
        plantIssueDao.insertIssue(issue(plantId, name = "C", resolvedAt = 5000L))

        assertEquals(2, plantIssueDao.getActiveIssueCountForPlant(plantId))
    }

    @Test
    fun `getAllIssues returns issues across all plants`() = runTest {
        val plantAId = insertParentPlant(name = "PlantA")
        val plantBId = insertParentPlant(name = "PlantB")
        plantIssueDao.insertIssue(issue(plantAId, name = "A-issue", startedAt = 1000L))
        plantIssueDao.insertIssue(issue(plantBId, name = "B-issue", startedAt = 2000L))

        plantIssueDao.getAllIssues().test {
            val list = awaitItem()
            assertEquals(listOf("A-issue", "B-issue"), list.map { it.name })
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getIssuesForPlant returns all issues for the plant ordered by startedAt`() = runTest {
        val plantId = insertParentPlant()
        plantIssueDao.insertIssue(issue(plantId, name = "Second", startedAt = 2000L))
        plantIssueDao.insertIssue(issue(plantId, name = "First", startedAt = 1000L))

        plantIssueDao.getIssuesForPlant(plantId).test {
            assertEquals(listOf("First", "Second"), awaitItem().map { it.name })
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getIssueById returns null for unknown id`() = runTest {
        assertNull(plantIssueDao.getIssueById(999L))
    }

    @Test
    fun `getIssueById returns the correct issue`() = runTest {
        val plantId = insertParentPlant()
        val id = plantIssueDao.insertIssue(issue(plantId, name = "Fungus gnats"))

        val result = plantIssueDao.getIssueById(id)
        assertNotNull(result)
        assertEquals("Fungus gnats", result?.name)
    }

    @Test
    fun `updateIssue persists a resolution`() = runTest {
        val plantId = insertParentPlant()
        val id = plantIssueDao.insertIssue(issue(plantId))
        val resolved = issue(plantId).copy(id = id, resolvedAt = 5000L, resolutionNote = "Treated")

        plantIssueDao.updateIssue(resolved)

        val result = plantIssueDao.getIssueById(id)
        assertEquals(5000L, result?.resolvedAt)
        assertEquals("Treated", result?.resolutionNote)
    }

    @Test
    fun `deleteIssue removes it from the plant's issues`() = runTest {
        val plantId = insertParentPlant()
        val id = plantIssueDao.insertIssue(issue(plantId))
        val entity = issue(plantId).copy(id = id)

        plantIssueDao.deleteIssue(entity)

        plantIssueDao.getIssuesForPlant(plantId).test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `cascading delete removes plant issues when plant is deleted`() = runTest {
        val plantId = insertParentPlant()
        plantIssueDao.insertIssue(issue(plantId))

        plantDao.deleteAll()

        plantIssueDao.getIssuesForPlant(plantId).test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `deleteAll removes every issue`() = runTest {
        val plantId = insertParentPlant()
        plantIssueDao.insertIssue(issue(plantId, name = "A"))
        plantIssueDao.insertIssue(issue(plantId, name = "B"))

        plantIssueDao.deleteAll()

        assertEquals(0, plantIssueDao.getIssuesForPlant(plantId).first().size)
    }

    @Test
    fun `insertAll persists every provided issue`() = runTest {
        val plantId = insertParentPlant()
        val ids = plantIssueDao.insertAll(
            listOf(
                issue(plantId, name = "A"),
                issue(plantId, name = "B")
            )
        )
        assertEquals(2, ids.size)
        assertEquals(2, plantIssueDao.getIssuesForPlant(plantId).first().size)
    }
}
