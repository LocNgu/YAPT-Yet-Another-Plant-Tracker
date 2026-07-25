package com.yapt.planttracker.worker

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yapt.planttracker.MainActivity
import com.yapt.planttracker.R
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.notification.NotificationHelper
import com.yapt.planttracker.notification.SkipWateringReceiver
import com.yapt.planttracker.util.toLocalDate
import kotlinx.coroutines.flow.first
import java.time.temporal.ChronoUnit

class ReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        val app = context.applicationContext as YaptApplication
        val plants = app.plantRepository.getAllPlants().first()
        val now = System.currentTimeMillis()
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        // Cancel all existing notifications (covers deleted plants whose IDs are no longer in `plants`)
        notificationManager.cancelAll()

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

            val status = CareSchedule.computeStatus(
                plant = plant,
                lastWateredAt = lastWatering?.loggedAt,
                lastFertilizedAt = lastFertilizing?.loggedAt,
                totalLogs = 0,
                now = now
            )

            if (!status.isOverdue && !status.isDueSoon &&
                !status.isFertilizingOverdue && !status.isFertilizingDueSoon
            ) {
                continue
            }

            val body = buildCareBody(status, now)
            if (body.isEmpty()) continue

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

        return Result.success()
    }

    private fun buildCareBody(status: PlantCareStatus, now: Long): String {
        val parts = mutableListOf<String>()
        val nowDate = now.toLocalDate()

        if (status.isOverdue) {
            val days = ChronoUnit.DAYS.between(status.nextWateringDueAt!!.toLocalDate(), nowDate).toInt()
            parts.add(context.resources.getQuantityString(R.plurals.notification_watering_overdue, days, days))
        } else if (status.isDueSoon) {
            parts.add(context.getString(R.string.notification_watering_due_today))
        }

        if (status.isFertilizingOverdue || status.isFertilizingDueSoon) {
            if (status.plant.useLiquidFertilizer) {
                if (parts.isNotEmpty()) parts.add(context.getString(R.string.notification_fertilize_with_watering))
            } else {
                if (status.isFertilizingOverdue) {
                    val days = ChronoUnit.DAYS.between(status.nextFertilizingDueAt!!.toLocalDate(), nowDate).toInt()
                    parts.add(
                        context.resources.getQuantityString(R.plurals.notification_fertilizing_overdue, days, days)
                    )
                } else {
                    parts.add(context.getString(R.string.notification_fertilizing_due_today))
                }
            }
        }

        return parts.joinToString(" · ")
    }

    companion object {
        const val EXTRA_PLANT_ID = "plantId"
        const val WORK_NAME = "yapt_care_reminder"
    }
}
