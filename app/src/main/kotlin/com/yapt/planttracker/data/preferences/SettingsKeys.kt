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
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val PHOTO_REMINDER_ENABLED = booleanPreferencesKey("photo_reminder_enabled")
    val COMBINE_NOTIFICATIONS = booleanPreferencesKey("combine_notifications")
    val THEME_MODE = stringPreferencesKey("theme_mode")
}

/**
 * Default values used as fallbacks when a preference key is absent. Kept in one place so the
 * daily-reminder default time (9:00) is not repeated as a magic number across MainActivity,
 * BootReceiver, YaptApplication, BackupManager, and SettingsViewModel.
 */
object SettingsDefaults {
    const val REMINDER_HOUR = 9
    const val REMINDER_MINUTE = 0
}
