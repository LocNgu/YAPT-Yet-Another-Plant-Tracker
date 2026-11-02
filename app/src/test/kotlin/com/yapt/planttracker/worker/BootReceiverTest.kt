package com.yapt.planttracker.worker

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.settingsDataStore
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

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

    // Dispatched through sendBroadcast, not a direct onReceive call: only a dispatched broadcast carries
    // the PendingResult that goAsync() hands out. A real device can't be used, because BOOT_COMPLETED is
    // a protected broadcast that neither the app nor the shell uid may send (#499).
    @Test
    fun `boot broadcast goes async, enqueues the daily reminder and finishes the pending result`() {
        runBlocking { context.settingsDataStore.edit { it[SettingsKeys.NOTIFICATIONS_ENABLED] = true } }
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        val receiver = shadowOf(context as Application).getReceiversForIntent(intent)
            .filterIsInstance<BootReceiver>().single()
        assertEquals(0, reminderWork().size)

        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(shadowOf(receiver).wentAsync())
        shadowOf(shadowOf(receiver).originalPendingResult).future.get(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val infos = reminderWork()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
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

    @Test
    fun `rescheduleFromStoredPrefs passes the stored reminder time to the scheduler`() = runBlocking {
        context.settingsDataStore.edit {
            it[SettingsKeys.NOTIFICATIONS_ENABLED] = true
            it[SettingsKeys.REMINDER_HOUR] = 21
            it[SettingsKeys.REMINDER_MINUTE] = 30
        }
        mockkObject(ReminderScheduler)
        try {
            every { ReminderScheduler.schedule(any(), any(), any()) } just Runs

            BootReceiver().rescheduleFromStoredPrefs(context)

            verify { ReminderScheduler.schedule(any(), 21, 30) }
        } finally {
            unmockkObject(ReminderScheduler)
        }
    }

    private companion object {
        const val FINISH_TIMEOUT_SECONDS = 5L
    }
}
