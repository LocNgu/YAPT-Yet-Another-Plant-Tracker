package com.yapt.planttracker.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yapt.planttracker.settingsDataStore
import com.yapt.planttracker.ui.screens.settings.SettingsViewModel
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
                val enabled = prefs[SettingsViewModel.KEY_NOTIFICATIONS_ENABLED] ?: true
                if (enabled) {
                    val hour = prefs[SettingsViewModel.KEY_REMINDER_HOUR] ?: 9
                    val minute = prefs[SettingsViewModel.KEY_REMINDER_MINUTE] ?: 0
                    ReminderScheduler.schedule(context, hour, minute)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
