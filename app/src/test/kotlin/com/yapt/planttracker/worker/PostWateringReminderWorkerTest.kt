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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
        notificationManager = app.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
        runBlocking {
            app.settingsDataStore.edit {
                it[SettingsKeys.NOTIFICATIONS_ENABLED] = true
                it[SettingsKeys.POST_WATERING_REMINDER_ENABLED] = true
            }
        }
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
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
    }
}
