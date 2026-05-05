package com.yapt.planttracker.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

object SettingsKeys {
    val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    val REMINDER_HOUR = intPreferencesKey("reminder_hour")
    val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
}
