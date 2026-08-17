package com.yapt.planttracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.yapt.planttracker.data.entity.CustomReminderEntity
import com.yapt.planttracker.data.entity.PlantEntity
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
class CustomReminderDaoTest {

    private lateinit var db: PlantDatabase
    private lateinit var plantDao: PlantDao
    private lateinit var customReminderDao: CustomReminderDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        plantDao = db.plantDao()
        customReminderDao = db.customReminderDao()
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

    private fun reminder(
        plantId: Long,
        name: String = "Neem oil treatment",
        intervalDays: Int = 7,
        lastDoneAt: Long? = null,
        createdAt: Long = 1_000_000L
    ) = CustomReminderEntity(
        plantId = plantId,
        name = name,
        intervalDays = intervalDays,
        lastDoneAt = lastDoneAt,
        createdAt = createdAt
    )

    @Test
    fun `insertReminder and getRemindersForPlant returns inserted reminder`() = runTest {
        val plantId = insertParentPlant()
        customReminderDao.insertReminder(reminder(plantId))

        customReminderDao.getRemindersForPlant(plantId).test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals("Neem oil treatment", list[0].name)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `getRemindersForPlantOnce returns all reminders for the plant ordered by createdAt`() = runTest {
        val plantId = insertParentPlant()
        customReminderDao.insertReminder(reminder(plantId, name = "Second", createdAt = 2000L))
        customReminderDao.insertReminder(reminder(plantId, name = "First", createdAt = 1000L))

        val result = customReminderDao.getRemindersForPlantOnce(plantId)
        assertEquals(listOf("First", "Second"), result.map { it.name })
    }

    @Test
    fun `getReminderById returns null for unknown id`() = runTest {
        assertNull(customReminderDao.getReminderById(999L))
    }

    @Test
    fun `getReminderById returns the correct reminder`() = runTest {
        val plantId = insertParentPlant()
        val id = customReminderDao.insertReminder(reminder(plantId, name = "Fungicide"))

        val result = customReminderDao.getReminderById(id)
        assertNotNull(result)
        assertEquals("Fungicide", result?.name)
    }

    @Test
    fun `updateReminder persists changes`() = runTest {
        val plantId = insertParentPlant()
        val id = customReminderDao.insertReminder(reminder(plantId, intervalDays = 7))
        val updated = reminder(plantId, intervalDays = 14).copy(id = id, lastDoneAt = 5000L)

        customReminderDao.updateReminder(updated)

        val result = customReminderDao.getReminderById(id)
        assertEquals(14, result?.intervalDays)
        assertEquals(5000L, result?.lastDoneAt)
    }

    @Test
    fun `deleteReminder removes it from the plant's reminders`() = runTest {
        val plantId = insertParentPlant()
        val id = customReminderDao.insertReminder(reminder(plantId))
        val entity = reminder(plantId).copy(id = id)

        customReminderDao.deleteReminder(entity)

        customReminderDao.getRemindersForPlant(plantId).test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `cascading delete removes custom reminders when plant is deleted`() = runTest {
        val plantId = insertParentPlant()
        customReminderDao.insertReminder(reminder(plantId))

        plantDao.deleteAll()

        customReminderDao.getRemindersForPlant(plantId).test {
            assertEquals(0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `deleteAll removes every reminder`() = runTest {
        val plantId = insertParentPlant()
        customReminderDao.insertReminder(reminder(plantId, name = "A"))
        customReminderDao.insertReminder(reminder(plantId, name = "B"))

        customReminderDao.deleteAll()

        assertEquals(0, customReminderDao.getRemindersForPlantOnce(plantId).size)
    }

    @Test
    fun `insertAll persists every provided reminder`() = runTest {
        val plantId = insertParentPlant()
        val ids = customReminderDao.insertAll(
            listOf(
                reminder(plantId, name = "A"),
                reminder(plantId, name = "B")
            )
        )
        assertEquals(2, ids.size)
        assertEquals(2, customReminderDao.getRemindersForPlantOnce(plantId).size)
    }
}
