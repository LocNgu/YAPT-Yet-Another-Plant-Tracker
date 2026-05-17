package com.yapt.planttracker.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object SettingsKeys {
    val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    val REMINDER_HOUR = intPreferencesKey("reminder_hour")
    val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
    val SORT_OPTION = stringPreferencesKey("sort_option")
    val SORT_ASCENDING = booleanPreferencesKey("sort_ascending")
    val LAST_SEEN_VERSION_CODE = intPreferencesKey("last_seen_version_code")
}
