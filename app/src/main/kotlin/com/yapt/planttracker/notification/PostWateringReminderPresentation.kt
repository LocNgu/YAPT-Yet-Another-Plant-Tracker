package com.yapt.planttracker.notification

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.yapt.planttracker.data.preferences.SettingsKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Coordinates the mutually-exclusive in-app and system presentations for the drain-water reminder. */
object PostWateringReminderPresentation {

    const val SYSTEM_NOTIFICATION_ID = -2
    const val EXTRA_SHOW_CARED_TODAY = "showCaredToday"

    fun pendingPrompt(dataStore: DataStore<Preferences>): Flow<Long?> = dataStore.data
        .map { it[SettingsKeys.POST_WATERING_REMINDER_PENDING_AT] }
        .distinctUntilChanged()

    suspend fun showInApp(
        context: Context,
        dataStore: DataStore<Preferences>,
        triggeredAt: Long = System.currentTimeMillis()
    ) {
        dataStore.edit { it[SettingsKeys.POST_WATERING_REMINDER_PENDING_AT] = triggeredAt }
        cancelSystemNotification(context)
    }

    suspend fun clearPendingPrompt(dataStore: DataStore<Preferences>) {
        dataStore.edit { it.remove(SettingsKeys.POST_WATERING_REMINDER_PENDING_AT) }
    }

    suspend fun dismiss(
        context: Context,
        dataStore: DataStore<Preferences>,
        expectedTriggeredAt: Long
    ) {
        var dismissed = false
        dataStore.edit { prefs ->
            if (prefs[SettingsKeys.POST_WATERING_REMINDER_PENDING_AT] == expectedTriggeredAt) {
                prefs.remove(SettingsKeys.POST_WATERING_REMINDER_PENDING_AT)
                dismissed = true
            }
        }
        if (dismissed) cancelSystemNotification(context)
    }

    suspend fun clear(context: Context, dataStore: DataStore<Preferences>) {
        clearPendingPrompt(dataStore)
        cancelSystemNotification(context)
    }

    private fun cancelSystemNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(SYSTEM_NOTIFICATION_ID)
    }
}
