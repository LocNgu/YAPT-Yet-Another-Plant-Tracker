package com.yapt.planttracker

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

internal suspend fun writeDefaultReminderTimeIfAbsent(dataStore: DataStore<Preferences>) {
    val prefs = dataStore.data.first()
    if (prefs[SettingsKeys.REMINDER_HOUR] == null) {
        dataStore.edit {
            it[SettingsKeys.REMINDER_HOUR] = 9
            it[SettingsKeys.REMINDER_MINUTE] = 0
        }
    }
}

class YaptApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { PlantDatabase.getInstance(this) }

    val plantRepository by lazy { PlantRepository(database.plantDao()) }
    val careLogRepository by lazy { CareLogRepository(database.careLogDao()) }
    val plantPhotoRepository by lazy { PlantPhotoRepository(database.plantPhotoDao()) }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        applicationScope.launch {
            writeDefaultReminderTimeIfAbsent(settingsDataStore)
        }
    }
}
