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
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

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
            } else null
            val lastFertilizing = if (plant.fertilizingIntervalDays != null) {
                app.careLogRepository.getLastLogOfType(plant.id, CareType.FERTILIZE)
            } else null

            val status = CareSchedule.computeStatus(
                plant = plant,
                lastWateredAt = lastWatering?.loggedAt,
                lastFertilizedAt = lastFertilizing?.loggedAt,
                totalLogs = 0,
                now = now
            )

            if (!status.isOverdue && !status.isDueSoon &&
                !status.isFertilizingOverdue && !status.isFertilizingDueSoon
            ) continue

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

            val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_plant_placeholder)
                .setContentTitle(plant.name)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(plant.id.toInt(), notification)
        }

        return Result.success()
    }

    private fun buildCareBody(status: PlantCareStatus, now: Long): String {
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val parts = mutableListOf<String>()

        if (status.isOverdue) {
            val days = ((now - status.nextWateringDueAt!!) / dayMs).toInt().coerceAtLeast(1)
            parts.add("Watering overdue by $days ${if (days == 1) "day" else "days"}")
        } else if (status.isDueSoon) {
            parts.add("Watering due today")
        }

        if (status.isFertilizingOverdue) {
            val days = ((now - status.nextFertilizingDueAt!!) / dayMs).toInt().coerceAtLeast(1)
            parts.add("Fertilizing overdue by $days ${if (days == 1) "day" else "days"}")
        } else if (status.isFertilizingDueSoon) {
            parts.add("Fertilizing due today")
        }

        return if (parts.size > 1) "Watering and fertilizing due" else parts.firstOrNull() ?: ""
    }

    companion object {
        const val EXTRA_PLANT_ID = "plantId"
        const val WORK_NAME = "yapt_care_reminder"
    }
}
