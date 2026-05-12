package com.yapt.planttracker

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.notification.NotificationHelper

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class YaptApplication : Application() {

    val database by lazy { PlantDatabase.getInstance(this) }

    val plantRepository by lazy { PlantRepository(database.plantDao()) }
    val careLogRepository by lazy { CareLogRepository(database.careLogDao()) }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
    }
}
