package com.yapt.planttracker.worker

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.settingsDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BootReceiverTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // SynchronousExecutor makes enqueue apply immediately so assertions can read the result.
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        workManager = WorkManager.getInstance(context)
    }

    private fun reminderWork(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(ReminderWorker.WORK_NAME).get()

    @Test
    fun `onReceive ignores non-boot actions`() {
        BootReceiver().onReceive(context, Intent("com.example.SOMETHING_ELSE"))

        assertEquals(0, reminderWork().size)
    }

    @Test
    fun `rescheduleFromStoredPrefs enqueues the daily reminder when notifications are enabled`() = runBlocking {
        context.settingsDataStore.edit { it[SettingsKeys.NOTIFICATIONS_ENABLED] = true }

        BootReceiver().rescheduleFromStoredPrefs(context)

        val infos = reminderWork()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
    }

    @Test
    fun `rescheduleFromStoredPrefs enqueues nothing when notifications are disabled`() = runBlocking {
        context.settingsDataStore.edit { it[SettingsKeys.NOTIFICATIONS_ENABLED] = false }

        BootReceiver().rescheduleFromStoredPrefs(context)

        assertEquals(0, reminderWork().size)
    }
}
