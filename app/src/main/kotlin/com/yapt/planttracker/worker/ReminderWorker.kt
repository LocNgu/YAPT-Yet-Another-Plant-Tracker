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
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.notification.NotificationHelper
import kotlinx.coroutines.flow.first

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
        val overduePlants = mutableListOf<String>()

        for (plant in plants) {
            val interval = plant.wateringIntervalDays ?: continue
            val lastWatering = app.careLogRepository.getLastLogOfType(plant.id, CareType.WATER)
            val status = CareSchedule.computeStatus(
                plant = plant,
                lastWateredAt = lastWatering?.loggedAt,
                lastFertilizedAt = null,
                totalLogs = 0,
                now = now
            )
            if (status.isOverdue || status.isDueSoon) {
                overduePlants.add(plant.name)
            }
        }

        if (overduePlants.isEmpty()) return Result.success()

        val title = if (overduePlants.size == 1) {
            "${overduePlants[0]} needs watering!"
        } else {
            "${overduePlants.size} plants need watering"
        }
        val body = if (overduePlants.size == 1) {
            "Don't forget to water your plant today."
        } else {
            overduePlants.joinToString(", ")
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_plant_placeholder)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)

        return Result.success()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val WORK_NAME = "yapt_care_reminder"
    }
}
