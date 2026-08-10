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
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.notification.CareReminderItem
import com.yapt.planttracker.domain.notification.DuePlantReminder
import com.yapt.planttracker.domain.notification.ReminderNotificationComposer
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.notification.NotificationHelper
import com.yapt.planttracker.notification.NotificationPermission
import com.yapt.planttracker.notification.SkipWateringReceiver
import com.yapt.planttracker.settingsDataStore
import kotlinx.coroutines.flow.first

class ReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!NotificationPermission.isGranted(context)) {
            return Result.success()
        }

        val app = context.applicationContext as YaptApplication
        val plants = app.plantRepository.getAllPlants().first()
        val now = System.currentTimeMillis()
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        // Cancel all existing notifications (covers deleted plants whose IDs are no longer in `plants`,
        // and self-heals if the user switched combine-notifications mode since the last run)
        notificationManager.cancelAll()

        val statuses = mutableListOf<PlantCareStatus>()
        for (plant in plants) {
            val lastWatering = if (plant.wateringIntervalDays != null) {
                app.careLogRepository.getLastLogOfType(plant.id, CareType.WATER)
            } else {
                null
            }
            val lastFertilizing = if (plant.fertilizingIntervalDays != null) {
                app.careLogRepository.getLastLogOfType(plant.id, CareType.FERTILIZE)
            } else {
                null
            }
            val lastRepotting = if (plant.repottingIntervalDays != null) {
                app.careLogRepository.getLastLogOfType(plant.id, CareType.REPOT)
            } else {
                null
            }

            statuses.add(
                CareSchedule.computeStatus(
                    plant = plant,
                    lastWateredAt = lastWatering?.loggedAt,
                    lastFertilizedAt = lastFertilizing?.loggedAt,
                    totalLogs = 0,
                    now = now,
                    lastRepottedAt = lastRepotting?.loggedAt
                )
            )
        }

        val prefs = context.settingsDataStore.data.first()
        val fertilizingNotificationsEnabled = prefs[SettingsKeys.FERTILIZING_NOTIFICATIONS_ENABLED] ?: true
        val dueReminders = ReminderNotificationComposer.computeDueReminders(
            statuses,
            now,
            fertilizingNotificationsEnabled
        )

        if (dueReminders.isNotEmpty()) {
            val combineNotifications = prefs[SettingsKeys.COMBINE_NOTIFICATIONS] ?: false

            if (combineNotifications) {
                postCombinedNotification(notificationManager, dueReminders.size)
            } else {
                for (reminder in dueReminders) {
                    postPlantNotification(notificationManager, reminder)
                }
            }
        }

        return Result.success()
    }

    private fun postCombinedNotification(notificationManager: NotificationManager, dueCount: Int) {
        val title = context.resources.getQuantityString(R.plurals.notification_combined_title, dueCount, dueCount)

        val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            COMBINED_NOTIFICATION_ID,
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_plant_placeholder)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(COMBINED_NOTIFICATION_ID, notification)
    }

    private fun postPlantNotification(notificationManager: NotificationManager, reminder: DuePlantReminder) {
        val status = reminder.status
        val plant = status.plant
        val body = buildCareBody(reminder.items)

        val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PLANT_ID, plant.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            plant.id.toInt(),
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val skipIntent = Intent(context, SkipWateringReceiver::class.java).apply {
            action = SkipWateringReceiver.ACTION_SKIP_WATERING
            putExtra(SkipWateringReceiver.EXTRA_PLANT_ID, plant.id)
        }
        val skipPendingIntent = PendingIntent.getBroadcast(
            context,
            plant.id.toInt(),
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_plant_placeholder)
            .setContentTitle(plant.name)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (status.isOverdue || status.isDueSoon) {
            notificationBuilder.addAction(0, context.getString(R.string.skip_watering_title), skipPendingIntent)
        }

        notificationManager.notify(plant.id.toInt(), notificationBuilder.build())
    }

    private fun buildCareBody(items: List<CareReminderItem>): String =
        items.joinToString(" · ") { item ->
            when (item) {
                is CareReminderItem.WateringOverdue ->
                    context.resources.getQuantityString(
                        R.plurals.notification_watering_overdue,
                        item.days,
                        item.days
                    )
                CareReminderItem.WateringDueToday -> context.getString(R.string.notification_watering_due_today)
                is CareReminderItem.FertilizingOverdue ->
                    context.resources.getQuantityString(
                        R.plurals.notification_fertilizing_overdue,
                        item.days,
                        item.days
                    )
                CareReminderItem.FertilizingDueToday ->
                    context.getString(R.string.notification_fertilizing_due_today)
                CareReminderItem.FertilizeWithWatering ->
                    context.getString(R.string.notification_fertilize_with_watering)
                is CareReminderItem.RepottingOverdue ->
                    context.resources.getQuantityString(
                        R.plurals.notification_repotting_overdue,
                        item.days,
                        item.days
                    )
                CareReminderItem.RepottingDueToday ->
                    context.getString(R.string.notification_repotting_due_today)
            }
        }

    companion object {
        const val EXTRA_PLANT_ID = "plantId"
        const val WORK_NAME = "yapt_care_reminder"
        const val COMBINED_NOTIFICATION_ID = -1
    }
}
