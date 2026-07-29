package com.yapt.planttracker.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // SynchronousExecutor makes enqueue/cancel apply immediately, so assertions can read
        // the resulting WorkInfo without waiting.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build()
        )
        workManager = WorkManager.getInstance(context)
    }

    private fun reminderWork(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(ReminderWorker.WORK_NAME).get()

    @Test
    fun `schedule enqueues a single daily periodic reminder work`() {
        ReminderScheduler.schedule(context, hour = 8, minute = 30)

        val infos = reminderWork()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)

        val periodicity = infos[0].periodicityInfo
        assertNotNull("Reminder work should be periodic", periodicity)
        assertEquals(TimeUnit.DAYS.toMillis(1), periodicity!!.repeatIntervalMillis)
    }

    @Test
    fun `schedule replaces the existing work rather than stacking duplicates`() {
        ReminderScheduler.schedule(context, hour = 8, minute = 0)
        ReminderScheduler.schedule(context, hour = 21, minute = 15)

        // REPLACE policy on a unique work name keeps exactly one enqueued request.
        assertEquals(1, reminderWork().size)
    }

    @Test
    fun `cancel removes the scheduled reminder work`() {
        ReminderScheduler.schedule(context, hour = 9, minute = 0)
        assertEquals(WorkInfo.State.ENQUEUED, reminderWork()[0].state)

        ReminderScheduler.cancel(context)

        assertEquals(WorkInfo.State.CANCELLED, reminderWork()[0].state)
    }
}
