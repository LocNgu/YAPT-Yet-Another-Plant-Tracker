package com.yapt.planttracker.worker

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.domain.model.Plant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderWorkerTest {

    private lateinit var app: YaptApplication
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        clearDatabase()
        notificationManager = app.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        clearDatabase()
        notificationManager.cancelAll()
    }

    // Room's synchronous clearAllTables() would run on Robolectric's main thread and throw;
    // the DAO deletes are suspend and dispatch off it. Children before parents for the FK.
    private fun clearDatabase() = runBlocking {
        app.database.careLogDao().deleteAll()
        app.database.plantPhotoDao().deleteAll()
        app.database.plantDao().deleteAll()
    }

    private fun runWorker(): ListenableWorker.Result =
        runBlocking { TestListenableWorkerBuilder<ReminderWorker>(app).build().doWork() }

    @Test
    fun `doWork returns success and posts nothing when notification permission is denied`() = runBlocking {
        // POST_NOTIFICATIONS is denied by default in Robolectric. Add a due plant to prove the
        // permission gate short-circuits before any notification is posted.
        app.plantRepository.addPlant(
            Plant(name = "Monstera", wateringIntervalDays = 3, createdAt = 0L, updatedAt = 0L)
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `doWork posts a reminder for a due plant when permission is granted`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // A never-watered plant with a watering interval is due today, so it yields one reminder.
        app.plantRepository.addPlant(
            Plant(name = "Fern", wateringIntervalDays = 5, createdAt = 0L, updatedAt = 0L)
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, shadowOf(notificationManager).size())
    }

    @Test
    fun `doWork posts nothing when no plant is due`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // No watering interval configured -> not scheduled -> no reminder.
        app.plantRepository.addPlant(
            Plant(name = "Cactus", wateringIntervalDays = null, createdAt = 0L, updatedAt = 0L)
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, shadowOf(notificationManager).size())
    }
}
