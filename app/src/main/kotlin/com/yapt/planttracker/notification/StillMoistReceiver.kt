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

/**
 * Handles the "Still moist" notification action (#570, `check_reminders` feature flag) — a
 * no-dialog, single-tap action mirroring [SkipWateringReceiver]'s shape. All of the actual
 * observation/scheduling logic (the `CareType.CHECK` log, the `wateringDueDateOverride` advance,
 * and the conditional adaptive-model feed) lives in [com.yapt.planttracker.domain.usecase
 * .QuickLogUseCase.recordStillMoistCheck] — the one choke point that also owns the same-day
 * dedupe guard, so this receiver stays a thin dispatcher.
 */
class StillMoistReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STILL_MOIST) return
        val plantId = intent.getLongExtra(EXTRA_PLANT_ID, 0L).takeIf { it != 0L } ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleStillMoist(context, plantId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Pulled out of [goAsync] so it's directly testable, mirroring [SkipWateringReceiver.skipWatering]. */
    internal suspend fun handleStillMoist(context: Context, plantId: Long) {
        val app = context.applicationContext as YaptApplication
        val plant = app.plantRepository.getPlantById(plantId).first() ?: return
        app.quickLogUseCase.recordStillMoistCheck(plant)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(plantId.toInt())
    }

    companion object {
        const val EXTRA_PLANT_ID = "plantId"
        const val ACTION_STILL_MOIST = "com.yapt.planttracker.ACTION_STILL_MOIST"
    }
}
