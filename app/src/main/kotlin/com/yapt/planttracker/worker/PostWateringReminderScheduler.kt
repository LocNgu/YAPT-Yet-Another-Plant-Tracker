package com.yapt.planttracker.worker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.notification.PostWateringReminderNotificationComposer
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

object PostWateringReminderScheduler {

    const val WORK_NAME = "yapt_post_watering_reminder"
    const val DELAY_MINUTES = 30L

    suspend fun scheduleIfEnabled(
        context: Context,
        dataStore: DataStore<Preferences>,
        loggedAt: Long,
        now: Long = System.currentTimeMillis()
    ) {
        if (!PostWateringReminderNotificationComposer.shouldSchedule(loggedAt, now)) return

        val prefs = dataStore.data.first()
        val notificationsEnabled = prefs[SettingsKeys.NOTIFICATIONS_ENABLED] ?: true
        val postWateringEnabled = prefs[SettingsKeys.POST_WATERING_REMINDER_ENABLED] ?: true
        if (!notificationsEnabled || !postWateringEnabled) return

        val request = OneTimeWorkRequestBuilder<PostWateringReminderWorker>()
            .setInitialDelay(DELAY_MINUTES, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
