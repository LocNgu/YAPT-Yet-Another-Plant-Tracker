package com.yapt.planttracker.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.backup.BackupManager
import com.yapt.planttracker.data.backup.BackupResult
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.worker.ReminderScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val dataStore: DataStore<Preferences>,
    private val context: Context,
    private val database: PlantDatabase
) : ViewModel() {

    companion object {
        val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val KEY_REMINDER_HOUR = intPreferencesKey("reminder_hour")
        val KEY_REMINDER_MINUTE = intPreferencesKey("reminder_minute")
    }

    val notificationsEnabled: StateFlow<Boolean> = dataStore.data
        .map { it[KEY_NOTIFICATIONS_ENABLED] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val reminderHour: StateFlow<Int> = dataStore.data
        .map { it[KEY_REMINDER_HOUR] ?: 9 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 9)

    val reminderMinute: StateFlow<Int> = dataStore.data
        .map { it[KEY_REMINDER_MINUTE] ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _backupResult = MutableSharedFlow<BackupResult>()
    val backupResult: SharedFlow<BackupResult> = _backupResult.asSharedFlow()

    private val backupManager = BackupManager(context, database, dataStore)

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_NOTIFICATIONS_ENABLED] = enabled }
            if (enabled) {
                val prefs = dataStore.data.first()
                val hour = prefs[KEY_REMINDER_HOUR] ?: 9
                val minute = prefs[KEY_REMINDER_MINUTE] ?: 0
                ReminderScheduler.schedule(context, hour, minute)
            } else {
                ReminderScheduler.cancel(context)
            }
        }
    }

    fun setReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            dataStore.edit {
                it[KEY_REMINDER_HOUR] = hour
                it[KEY_REMINDER_MINUTE] = minute
            }
            val enabled = dataStore.data.first()[KEY_NOTIFICATIONS_ENABLED] ?: true
            if (enabled) {
                ReminderScheduler.schedule(context, hour, minute)
            }
        }
    }

    fun exportBackup(uri: Uri, includePhotos: Boolean) {
        viewModelScope.launch {
            val result = backupManager.exportBackup(uri, includePhotos)
            _backupResult.emit(result)
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            val result = backupManager.importBackup(uri)
            _backupResult.emit(result)
        }
    }

    fun proceedWithFutureSchemaImport(onProceed: suspend () -> BackupResult) {
        viewModelScope.launch {
            val result = runCatching { onProceed() }.getOrElse { e ->
                BackupResult.Error(e.message ?: "Import failed")
            }
            _backupResult.emit(result)
        }
    }

    class Factory(
        private val dataStore: DataStore<Preferences>,
        private val context: Context,
        private val database: PlantDatabase
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(dataStore, context, database) as T
    }
}
