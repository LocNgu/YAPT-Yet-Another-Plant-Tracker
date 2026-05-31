package com.yapt.planttracker.worker

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yapt.planttracker.YaptApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SkipWateringReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SKIP_WATERING) return
        val plantId = intent.getLongExtra(EXTRA_PLANT_ID, -1L)
        if (plantId == -1L) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as YaptApplication
                val plant = app.plantRepository.getPlantById(plantId).first()
                plant?.wateringIntervalDays?.let { current ->
                    app.plantRepository.updatePlant(
                        plant.copy(
                            wateringIntervalDays = current + 1,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                context.getSystemService(NotificationManager::class.java).cancel(plantId.toInt())
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
