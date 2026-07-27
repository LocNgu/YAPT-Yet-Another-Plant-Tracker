package com.yapt.planttracker.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yapt.planttracker.data.preferences.SettingsDefaults
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.settingsDataStore.data.first()
                val enabled = prefs[SettingsKeys.NOTIFICATIONS_ENABLED] ?: true
                if (enabled) {
                    val hour = prefs[SettingsKeys.REMINDER_HOUR] ?: SettingsDefaults.REMINDER_HOUR
                    val minute = prefs[SettingsKeys.REMINDER_MINUTE] ?: SettingsDefaults.REMINDER_MINUTE
                    ReminderScheduler.schedule(context, hour, minute)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
