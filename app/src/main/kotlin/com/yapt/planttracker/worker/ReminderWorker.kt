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
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.isFeatureEnabled
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.notification.CareReminderItem
import com.yapt.planttracker.domain.notification.DuePlantReminder
import com.yapt.planttracker.domain.notification.ReminderNotificationComposer
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import com.yapt.planttracker.notification.NotificationHelper
import com.yapt.planttracker.notification.NotificationPermission
import com.yapt.planttracker.notification.SkipWateringReceiver
import com.yapt.planttracker.notification.StillMoistReceiver
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

        val seasonalAmplitude = context.settingsDataStore.seasonalAmplitudeOnce()
        val hemisphere = SeasonalWatering.currentHemisphere()

        val statuses = mutableListOf<PlantCareStatus>()
        for (plant in plants) {
            statuses.add(buildStatus(app, plant, now, seasonalAmplitude, hemisphere))
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
            val checkRemindersEnabled = context.settingsDataStore.isFeatureEnabled(FeatureFlagRegistry.CHECK_REMINDERS)

            if (combineNotifications) {
                postCombinedNotification(notificationManager, dueReminders.size)
            } else {
                for (reminder in dueReminders) {
                    postPlantNotification(notificationManager, reminder, checkRemindersEnabled)
                }
            }
        }

        return Result.success()
    }

    @Suppress("LongParameterList")
    private suspend fun buildStatus(
        app: YaptApplication,
        plant: Plant,
        now: Long,
        seasonalAmplitude: Double,
        hemisphere: Hemisphere
    ): PlantCareStatus {
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
        val customReminders = app.customReminderRepository.getRemindersForPlantOnce(plant.id)

        return CareSchedule.computeStatus(
            plant = plant,
            lastWateredAt = lastWatering?.loggedAt,
            lastFertilizedAt = lastFertilizing?.loggedAt,
            totalLogs = 0,
            now = now,
            lastRepottedAt = lastRepotting?.loggedAt,
            customReminders = customReminders,
            seasonalAmplitude = seasonalAmplitude,
            hemisphere = hemisphere
        )
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

    private fun postPlantNotification(
        notificationManager: NotificationManager,
        reminder: DuePlantReminder,
        checkRemindersEnabled: Boolean
    ) {
        val status = reminder.status
        val plant = status.plant
        val body = buildCareBody(reminder.items)
        val isWateringDue = status.isOverdue || status.isDueSoon
        // Reframing only ever applies to a watering-due plant — a fertilizing/repotting-only
        // reminder has no "check the soil" action to offer, so it keeps the plain plant-name title.
        val showCheckReframing = isWateringDue && checkRemindersEnabled

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

        val notificationBuilder = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_plant_placeholder)
            .setContentTitle(
                if (showCheckReframing) context.getString(R.string.notification_check_title, plant.name) else plant.name
            )
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (isWateringDue) {
            if (showCheckReframing) {
                // "Watered" reuses the same deep-link as tapping the notification body (opens
                // the app to this plant, where the existing quick-water flow lives) — this action
                // is a discoverability affordance, not a new code path (#570).
                notificationBuilder.addAction(0, context.getString(R.string.notification_action_watered), pendingIntent)
                notificationBuilder.addAction(
                    0,
                    context.getString(R.string.notification_action_still_moist),
                    stillMoistPendingIntent(plant.id)
                )
            } else {
                notificationBuilder.addAction(
                    0,
                    context.getString(R.string.skip_watering_title),
                    skipPendingIntent(plant.id)
                )
            }
        }

        notificationManager.notify(plant.id.toInt(), notificationBuilder.build())
    }

    private fun skipPendingIntent(plantId: Long): PendingIntent {
        val skipIntent = Intent(context, SkipWateringReceiver::class.java).apply {
            action = SkipWateringReceiver.ACTION_SKIP_WATERING
            putExtra(SkipWateringReceiver.EXTRA_PLANT_ID, plantId)
        }
        return PendingIntent.getBroadcast(
            context,
            plantId.toInt(),
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Distinct request codes aren't required here (technical ADR-0007): a different target
    // component (StillMoistReceiver vs. SkipWateringReceiver) already makes these PendingIntents
    // distinct from skipPendingIntent()'s even when they share plantId.toInt() as the request code.
    private fun stillMoistPendingIntent(plantId: Long): PendingIntent {
        val stillMoistIntent = Intent(context, StillMoistReceiver::class.java).apply {
            action = StillMoistReceiver.ACTION_STILL_MOIST
            putExtra(StillMoistReceiver.EXTRA_PLANT_ID, plantId)
        }
        return PendingIntent.getBroadcast(
            context,
            plantId.toInt(),
            stillMoistIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
                is CareReminderItem.CustomReminderOverdue ->
                    context.resources.getQuantityString(
                        R.plurals.notification_custom_reminder_overdue,
                        item.days,
                        item.name,
                        item.days
                    )
                is CareReminderItem.CustomReminderDueToday ->
                    context.getString(R.string.notification_custom_reminder_due_today, item.name)
            }
        }

    companion object {
        const val EXTRA_PLANT_ID = "plantId"
        const val WORK_NAME = "yapt_care_reminder"
        const val COMBINED_NOTIFICATION_ID = -1
    }
}
