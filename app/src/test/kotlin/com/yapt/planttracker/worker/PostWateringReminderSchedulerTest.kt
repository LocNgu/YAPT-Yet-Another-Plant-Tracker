package com.yapt.planttracker.worker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.yapt.planttracker.data.preferences.SettingsKeys
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PostWateringReminderSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build()
        )
        workManager = WorkManager.getInstance(context)
    }

    private fun dataStore(enabled: Boolean = true): DataStore<Preferences> = mockk {
        every { data } returns flowOf(
            preferencesOf(
                SettingsKeys.NOTIFICATIONS_ENABLED to true,
                SettingsKeys.POST_WATERING_REMINDER_ENABLED to enabled
            )
        )
    }

    private fun reminderWork(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(PostWateringReminderScheduler.WORK_NAME).get()

    @Test
    fun `schedule enqueues one reminder about thirty minutes from now`() = runBlocking {
        val now = System.currentTimeMillis()

        PostWateringReminderScheduler.scheduleIfEnabled(context, dataStore(), loggedAt = now, now = now)

        val info = reminderWork().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        val delay = info.nextScheduleTimeMillis - now
        assertTrue(delay >= TimeUnit.MINUTES.toMillis(29))
        assertTrue(delay <= TimeUnit.MINUTES.toMillis(31))
    }

    @Test
    fun `later watering replaces the pending reminder instead of stacking`() = runBlocking {
        val now = System.currentTimeMillis()

        PostWateringReminderScheduler.scheduleIfEnabled(context, dataStore(), loggedAt = now, now = now)
        PostWateringReminderScheduler.scheduleIfEnabled(context, dataStore(), loggedAt = now + 1, now = now + 1)

        assertEquals(1, reminderWork().size)
    }

    @Test
    fun `disabled setting does not enqueue work`() = runBlocking {
        val now = System.currentTimeMillis()

        PostWateringReminderScheduler.scheduleIfEnabled(context, dataStore(enabled = false), loggedAt = now, now = now)

        assertEquals(0, reminderWork().size)
    }

    @Test
    fun `backdated watering does not enqueue work`() = runBlocking {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.of(2026, 9, 8).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val yesterday = LocalDate.of(2026, 9, 7).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()

        PostWateringReminderScheduler.scheduleIfEnabled(context, dataStore(), loggedAt = yesterday, now = now)

        assertEquals(0, reminderWork().size)
    }
}
