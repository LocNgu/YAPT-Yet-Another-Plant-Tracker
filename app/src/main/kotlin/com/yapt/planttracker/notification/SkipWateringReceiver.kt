package com.yapt.planttracker.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.util.plusCalendarDays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
     * `rescheduleFromStoredPrefs`, `.claude/rules/notifications.md`). Deliberately touches only
     * [com.yapt.planttracker.domain.model.Plant.wateringDueDateOverride] — never
     * `wateringConfidence`/`wateringIntervalDays`/`wateringBaseIntervalDays` — per product ADR-0007:
     * skip/reschedule is a calendar-only operation, not a learning signal (#570, product ADR-0027
     * records why it stays that way).
     *
     * Anchors to `maxOf(existing override, now) + 1 day`, mirroring
     * `rescheduledRelativeDueAt()` on Plant Detail — never to the existing override alone,
     * which could already be in the past and would then advance a stale date by only one day,
     * leaving the plant still overdue (#741). [now] is injectable so a test can pin the clock
     * relative to a fixed-date override (#733).
     */
    internal suspend fun skipWatering(
        context: Context,
        plantId: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        val app = context.applicationContext as YaptApplication
        val plant = app.plantRepository.getPlantById(plantId).first() ?: return
        val newOverride = maxOf(plant.wateringDueDateOverride ?: now, now).plusCalendarDays(1)
        app.plantRepository.updatePlant(
            plant.copy(
                wateringDueDateOverride = newOverride,
                updatedAt = now
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
