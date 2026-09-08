package com.yapt.planttracker.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yapt.planttracker.MainActivity
import com.yapt.planttracker.R
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.notification.PostWateringReminderNotificationComposer
import com.yapt.planttracker.notification.NotificationHelper
import com.yapt.planttracker.notification.NotificationPermission
import com.yapt.planttracker.settingsDataStore
import kotlinx.coroutines.flow.first

class PostWateringReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (NotificationPermission.isGranted(context) && reminderIsEnabled()) postNotification()
        return Result.success()
    }

    private suspend fun reminderIsEnabled(): Boolean {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.NOTIFICATIONS_ENABLED] != false &&
            prefs[SettingsKeys.POST_WATERING_REMINDER_ENABLED] != false
    }

    private fun postNotification() {
        val content = PostWateringReminderNotificationComposer.compose()
        val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SHOW_CARED_TODAY, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_plant_placeholder)
            .setContentTitle(context.getString(content.titleRes))
            .setContentText(context.getString(content.bodyRes))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(content.bodyRes)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val EXTRA_SHOW_CARED_TODAY = "showCaredToday"
        const val NOTIFICATION_ID = -2
    }
}
