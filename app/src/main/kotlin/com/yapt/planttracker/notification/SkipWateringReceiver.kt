package com.yapt.planttracker.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yapt.planttracker.YaptApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SkipWateringReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SKIP_WATERING) return
        val plantId = intent.getLongExtra(EXTRA_PLANT_ID, 0L).takeIf { it != 0L } ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                skipWatering(context, plantId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Pulled out of [goAsync] so it's directly testable (mirrors `BootReceiver`'s
     * `rescheduleFromStoredPrefs`, `.claude/rules/notifications.md`) — behavior is unchanged from
     * before this extraction. Deliberately touches only [com.yapt.planttracker.domain.model.Plant
     * .wateringDueDateOverride] — never `wateringConfidence`/`wateringIntervalDays`/
     * `wateringBaseIntervalDays` — per product ADR-0007: skip/reschedule is a calendar-only
     * operation, not a learning signal (#570, product ADR-0027 records why it stays that way).
     */
    internal suspend fun skipWatering(context: Context, plantId: Long) {
        val app = context.applicationContext as YaptApplication
        val plant = app.plantRepository.getPlantById(plantId).first() ?: return
        val newOverride = (plant.wateringDueDateOverride ?: System.currentTimeMillis()) +
            TimeUnit.DAYS.toMillis(1)
        app.plantRepository.updatePlant(
            plant.copy(
                wateringDueDateOverride = newOverride,
                updatedAt = System.currentTimeMillis()
            )
        )
        val notificationManager =
            context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(plantId.toInt())
    }

    companion object {
        const val EXTRA_PLANT_ID = "plantId"
        const val ACTION_SKIP_WATERING = "com.yapt.planttracker.ACTION_SKIP_WATERING"
    }
}
