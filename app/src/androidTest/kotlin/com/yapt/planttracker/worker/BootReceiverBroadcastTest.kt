package com.yapt.planttracker.worker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.settingsDataStore
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end check of the real [BootReceiver.onReceive] wiring: `goAsync()` -> launched coroutine ->
 * `pendingResult.finish()` (#499). Robolectric can't dispatch a real broadcast, so this runs on the
 * emulator (CI `instrumented` job only).
 *
 * `ACTION_BOOT_COMPLETED` is a protected broadcast an app process may not send, so it is sent through
 * the shell identity (`am broadcast`, explicitly targeted at [BootReceiver]). `am broadcast` blocks until
 * the broadcast completes, and a `goAsync()` receiver only completes once `finish()` is called, so a
 * returned "Broadcast completed" proves `finish()` ran and the receiver did not leak.
 */
class BootReceiverBroadcastTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(ReminderWorker.WORK_NAME).result.get()
        runBlocking { context.settingsDataStore.edit { it[SettingsKeys.NOTIFICATIONS_ENABLED] = true } }
    }

    @After
    fun tearDown() {
        workManager.cancelUniqueWork(ReminderWorker.WORK_NAME).result.get()
    }

    @Test
    fun bootCompletedBroadcast_enqueuesReminderWork_andFinishesPendingResult() {
        assertEquals(0, activeReminderWork().size)

        val output = sendBootCompletedBroadcast()

        assertTrue("am broadcast did not complete, i.e. finish() was never reached: $output",
            output.contains("Broadcast completed"))
        val infos = activeReminderWork()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
    }

    private fun activeReminderWork(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(ReminderWorker.WORK_NAME).get()
            .filter { !it.state.isFinished }

    private fun sendBootCompletedBroadcast(): String {
        val receiver = ComponentName(context.packageName, BootReceiver::class.java.name).flattenToShortString()
        val command = "am broadcast -a ${Intent.ACTION_BOOT_COMPLETED} -n $receiver"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
