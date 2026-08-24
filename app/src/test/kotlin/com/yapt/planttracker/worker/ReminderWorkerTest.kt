package com.yapt.planttracker.worker

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.settingsDataStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

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
        // Drop the per-test preference overrides so tests stay order-independent.
        runBlocking {
            app.settingsDataStore.edit {
                it.remove(SettingsKeys.FERTILIZING_NOTIFICATIONS_ENABLED)
                it.remove(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.CHECK_REMINDERS))
            }
        }
    }

    private fun setFertilizingNotificationsEnabled(enabled: Boolean) = runBlocking {
        app.settingsDataStore.edit { it[SettingsKeys.FERTILIZING_NOTIFICATIONS_ENABLED] = enabled }
    }

    private fun setCheckRemindersEnabled(enabled: Boolean) = runBlocking {
        app.settingsDataStore.edit { it[FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.CHECK_REMINDERS)] = enabled }
    }

    // Room's synchronous clearAllTables() would run on Robolectric's main thread and throw;
    // the DAO deletes are suspend and dispatch off it. Children before parents for the FK.
    private fun clearDatabase() = runBlocking {
        app.database.customReminderDao().deleteAll()
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
    fun `doWork posts a reminder for a plant overdue for repotting`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Never-repotted plant: first-due anchors at createdAt + interval (far in the past here),
        // so it is overdue and the worker's conditional lastRepotting query must fire.
        app.plantRepository.addPlant(
            Plant(name = "Bonsai", repottingIntervalDays = 365, createdAt = 0L, updatedAt = 0L)
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, shadowOf(notificationManager).size())
    }

    @Test
    fun `doWork still notifies a repotting-only plant when fertilizing notifications are off`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setFertilizingNotificationsEnabled(false)
        // Regression (#232 + #223): turning off fertilizing notifications must not suppress an
        // unrelated repotting reminder. Never repotted, created at epoch -> long overdue.
        app.plantRepository.addPlant(
            Plant(
                name = "Bonsai",
                wateringIntervalDays = null,
                repottingIntervalDays = 365,
                createdAt = 0L,
                updatedAt = 0L
            )
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, shadowOf(notificationManager).size())
    }

    @Test
    fun `doWork suppresses a fertilizing-only plant when fertilizing notifications are off`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setFertilizingNotificationsEnabled(false)
        // Non-liquid plant, created at epoch (past the 30-day fertilize grace), no watering interval
        // -> only fertilizing is due.
        app.plantRepository.addPlant(
            Plant(
                name = "Pothos",
                wateringIntervalDays = null,
                fertilizingIntervalDays = 14,
                createdAt = 0L,
                updatedAt = 0L
            )
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `doWork still notifies a watering-and-fertilizing plant when fertilizing notifications are off`() =
        runBlocking {
            shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            setFertilizingNotificationsEnabled(false)
            // Watering due today (never watered, has interval) AND fertilizing overdue -> full reminder.
            app.plantRepository.addPlant(
                Plant(
                    name = "Calathea",
                    wateringIntervalDays = 5,
                    fertilizingIntervalDays = 14,
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )

            val result = runWorker()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(1, shadowOf(notificationManager).size())
        }

    @Test
    fun `doWork posts a reminder for a plant overdue for a custom reminder`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val plantId = app.plantRepository.addPlant(
            Plant(name = "Sick Fiddle Leaf", createdAt = 0L, updatedAt = 0L)
        )
        app.customReminderRepository.addReminder(
            CustomReminder(
                plantId = plantId,
                name = "Neem oil treatment",
                intervalDays = 7,
                createdAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)
            )
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, shadowOf(notificationManager).size())
    }

    @Test
    fun `doWork keeps the plain title and Reschedule watering action when check_reminders is off`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.plantRepository.addPlant(
            Plant(name = "Fern", wateringIntervalDays = 5, createdAt = 0L, updatedAt = 0L)
        )

        runWorker()

        val notification = notificationManager.activeNotifications.first().notification
        assertEquals("Fern", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val actionTitles = notification.actions.orEmpty().map { it.title.toString() }
        assertEquals(listOf("Reschedule watering"), actionTitles)
    }

    @Test
    fun `doWork reframes to a Check title with Watered and Still moist actions when check_reminders is on`() =
        runBlocking {
            shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            setCheckRemindersEnabled(true)
            app.plantRepository.addPlant(
                Plant(name = "Fern", wateringIntervalDays = 5, createdAt = 0L, updatedAt = 0L)
            )

            runWorker()

            val notification = notificationManager.activeNotifications.first().notification
            assertEquals("Check Fern", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            val actionTitles = notification.actions.orEmpty().map { it.title.toString() }
            assertEquals(listOf("Watered", "Still moist"), actionTitles)
        }

    @Test
    fun `doWork does not reframe a repotting-only reminder even when check_reminders is on`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setCheckRemindersEnabled(true)
        // No watering interval -> not watering-due, so the check reframing must not apply even
        // though the flag is on; this reminder is repotting-only.
        app.plantRepository.addPlant(
            Plant(
                name = "Bonsai",
                wateringIntervalDays = null,
                repottingIntervalDays = 365,
                createdAt = 0L,
                updatedAt = 0L
            )
        )

        runWorker()

        val notification = notificationManager.activeNotifications.first().notification
        assertEquals("Bonsai", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(0, notification.actions.orEmpty().size)
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
