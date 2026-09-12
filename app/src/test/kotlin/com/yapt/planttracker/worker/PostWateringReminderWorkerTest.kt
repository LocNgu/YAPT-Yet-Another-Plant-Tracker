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
import com.yapt.planttracker.settingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PostWateringReminderWorkerTest {

    private lateinit var app: YaptApplication
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.setAppForeground(false)
        notificationManager = app.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
        shadowOf(app as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking {
            app.settingsDataStore.edit {
                it[SettingsKeys.NOTIFICATIONS_ENABLED] = true
                it[SettingsKeys.POST_WATERING_REMINDER_ENABLED] = true
                it.remove(SettingsKeys.POST_WATERING_REMINDER_PENDING_AT)
            }
        }
    }

    @After
    fun tearDown() {
        app.setAppForeground(false)
        notificationManager.cancelAll()
        runBlocking {
            app.settingsDataStore.edit { it.remove(SettingsKeys.POST_WATERING_REMINDER_PENDING_AT) }
        }
    }

    @Test
    fun `worker posts generic standing-water notification with cared-today deep link`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val result = TestListenableWorkerBuilder<PostWateringReminderWorker>(app).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val active = notificationManager.activeNotifications.single()
        assertEquals(PostWateringReminderWorker.NOTIFICATION_ID, active.id)
        assertEquals(
            "Check for standing water",
            active.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        )
        val intent = shadowOf(active.notification.contentIntent).savedIntent
        assertTrue(intent.getBooleanExtra(PostWateringReminderWorker.EXTRA_SHOW_CARED_TODAY, false))
        assertNull(app.settingsDataStore.data.first()[SettingsKeys.POST_WATERING_REMINDER_PENDING_AT])
    }

    @Test
    fun `foreground worker shows in-app reminder without notification permission`() = runBlocking {
        app.setAppForeground(true)

        val result = TestListenableWorkerBuilder<PostWateringReminderWorker>(app).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(notificationManager.activeNotifications.isEmpty())
        assertTrue(app.settingsDataStore.data.first()[SettingsKeys.POST_WATERING_REMINDER_PENDING_AT] != null)
    }

    @Test
    fun `background worker without notification permission has no presentation`() = runBlocking {
        val result = TestListenableWorkerBuilder<PostWateringReminderWorker>(app).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(notificationManager.activeNotifications.isEmpty())
        assertNull(app.settingsDataStore.data.first()[SettingsKeys.POST_WATERING_REMINDER_PENDING_AT])
    }

    @Test
    fun `disabled worker does not show foreground modal`() = runBlocking {
        app.setAppForeground(true)
        app.settingsDataStore.edit { it[SettingsKeys.POST_WATERING_REMINDER_ENABLED] = false }

        val result = TestListenableWorkerBuilder<PostWateringReminderWorker>(app).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(notificationManager.activeNotifications.isEmpty())
        assertNull(app.settingsDataStore.data.first()[SettingsKeys.POST_WATERING_REMINDER_PENDING_AT])
    }

    @Test
    fun `new firing replaces the prior presentation instead of showing both`() = runBlocking {
        app.setAppForeground(true)
        TestListenableWorkerBuilder<PostWateringReminderWorker>(app).build().doWork()
        assertTrue(app.settingsDataStore.data.first()[SettingsKeys.POST_WATERING_REMINDER_PENDING_AT] != null)

        app.setAppForeground(false)
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        TestListenableWorkerBuilder<PostWateringReminderWorker>(app).build().doWork()
        assertNull(app.settingsDataStore.data.first()[SettingsKeys.POST_WATERING_REMINDER_PENDING_AT])
        assertEquals(1, notificationManager.activeNotifications.size)

        app.setAppForeground(true)
        TestListenableWorkerBuilder<PostWateringReminderWorker>(app).build().doWork()
        assertTrue(notificationManager.activeNotifications.isEmpty())
        assertTrue(app.settingsDataStore.data.first()[SettingsKeys.POST_WATERING_REMINDER_PENDING_AT] != null)
    }
}
