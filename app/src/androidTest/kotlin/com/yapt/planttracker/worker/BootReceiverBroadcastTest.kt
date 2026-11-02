package com.yapt.planttracker.worker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
import org.junit.Before
import org.junit.Test

/**
 * End-to-end check of the real [BootReceiver.onReceive] wiring: `goAsync()` -> launched coroutine ->
 * `pendingResult.finish()` (#499). Robolectric can't dispatch a real broadcast, so this runs on the
 * emulator (CI `instrumented` job only).
 *
 * `ACTION_BOOT_COMPLETED` is a protected broadcast an app process may not send, so it is sent through
 * the shell identity (`am broadcast`, explicitly targeted at [BootReceiver]). The shell command returns
 * before the receiver's coroutine runs, so the test polls for the enqueued work. The work is only
 * enqueued by the coroutine that precedes `finish()`, and a `finish()` failure would crash the app
 * process and with it this instrumentation run.
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

        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var infos = activeReminderWork()
        while (infos.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(POLL_MS)
            infos = activeReminderWork()
        }
        assertEquals("no reminder work enqueued after boot broadcast; am output: $output", 1, infos.size)
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

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 100L
    }
}
