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
                val app = context.applicationContext as YaptApplication
                val plant = app.plantRepository.getPlantById(plantId).first() ?: return@launch
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
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_PLANT_ID = "plantId"
        const val ACTION_SKIP_WATERING = "com.yapt.planttracker.ACTION_SKIP_WATERING"
    }
}
