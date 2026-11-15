package com.yapt.planttracker.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.autobackup.AutoBackupFailureKind
import com.yapt.planttracker.domain.autobackup.AutoBackupFrequency
import com.yapt.planttracker.domain.autobackup.AutoBackupPolicy
import com.yapt.planttracker.domain.autobackup.AutoBackupRetention
import com.yapt.planttracker.domain.autobackup.AutoBackupStatus
import com.yapt.planttracker.notification.AutoBackupNotifier
import com.yapt.planttracker.worker.AutoBackupScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AutoBackupUiState(
    val enabled: Boolean = false,
    val folderLabel: String? = null,
    val frequency: AutoBackupFrequency = AutoBackupFrequency.DEFAULT,
    val retention: Int = AutoBackupRetention.DEFAULT,
    val includePhotos: Boolean = true,
    val optimizePhotos: Boolean = false,
    val status: AutoBackupStatus = AutoBackupStatus.Off
)

class AutoBackupViewModel(
    private val dataStore: DataStore<Preferences>,
    private val context: Context
) : ViewModel() {

    val state: StateFlow<AutoBackupUiState> = dataStore.data
        .map { prefs ->
            val folder = prefs[SettingsKeys.AUTO_BACKUP_FOLDER_URI]
            val enabled = prefs[SettingsKeys.AUTO_BACKUP_ENABLED] == true && folder != null
            AutoBackupUiState(
                enabled = enabled,
                folderLabel = folder?.let(::folderLabel),
                frequency = AutoBackupFrequency.fromStored(prefs[SettingsKeys.AUTO_BACKUP_FREQUENCY]),
                retention = AutoBackupRetention.fromStored(prefs[SettingsKeys.AUTO_BACKUP_RETENTION]),
                includePhotos = prefs[SettingsKeys.AUTO_BACKUP_INCLUDE_PHOTOS] ?: true,
                optimizePhotos = prefs[SettingsKeys.AUTO_BACKUP_OPTIMIZE_PHOTOS] ?: false,
                status = AutoBackupPolicy.status(
                    enabled = enabled,
                    lastFailure = AutoBackupFailureKind.fromStored(prefs[SettingsKeys.AUTO_BACKUP_LAST_FAILURE]),
                    lastSuccessAt = prefs[SettingsKeys.AUTO_BACKUP_LAST_SUCCESS_AT],
                    skippedPhotos = prefs[SettingsKeys.AUTO_BACKUP_LAST_SKIPPED_PHOTOS] ?: 0
                )
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AutoBackupUiState())

    fun hasFolder(): Boolean = state.value.folderLabel != null

    /** Grants persist across reboots; the picked tree replaces any earlier one and restarts the failure streak. */
    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val previous = dataStore.data.first()[SettingsKeys.AUTO_BACKUP_FOLDER_URI]
            if (previous != null && previous != uri.toString()) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        previous.toUri(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            }
            val prefs = dataStore.edit {
                it[SettingsKeys.AUTO_BACKUP_FOLDER_URI] = uri.toString()
                it[SettingsKeys.AUTO_BACKUP_ENABLED] = true
                resetRunState(it)
            }
            val frequency = AutoBackupFrequency.fromStored(prefs[SettingsKeys.AUTO_BACKUP_FREQUENCY])
            AutoBackupNotifier.clear(context)
            AutoBackupScheduler.schedule(context, frequency)
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val prefs = dataStore.edit {
                it[SettingsKeys.AUTO_BACKUP_ENABLED] = enabled && it[SettingsKeys.AUTO_BACKUP_FOLDER_URI] != null
            }
            if (enabled && prefs[SettingsKeys.AUTO_BACKUP_FOLDER_URI] != null) {
                val frequency = AutoBackupFrequency.fromStored(prefs[SettingsKeys.AUTO_BACKUP_FREQUENCY])
                AutoBackupScheduler.schedule(context, frequency)
            } else {
                AutoBackupScheduler.cancel(context)
                AutoBackupNotifier.clear(context)
            }
        }
    }

    fun setFrequency(frequency: AutoBackupFrequency) {
        viewModelScope.launch {
            val prefs = dataStore.edit { it[SettingsKeys.AUTO_BACKUP_FREQUENCY] = frequency.name }
            if (prefs[SettingsKeys.AUTO_BACKUP_ENABLED] == true) AutoBackupScheduler.schedule(context, frequency)
        }
    }

    fun setRetention(keep: Int) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.AUTO_BACKUP_RETENTION] = keep } }
    }

    fun setIncludePhotos(include: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.AUTO_BACKUP_INCLUDE_PHOTOS] = include } }
    }

    fun setOptimizePhotos(optimize: Boolean) {
        viewModelScope.launch { dataStore.edit { it[SettingsKeys.AUTO_BACKUP_OPTIMIZE_PHOTOS] = optimize } }
    }

    private fun resetRunState(prefs: MutablePreferences) {
        prefs.remove(SettingsKeys.AUTO_BACKUP_FINGERPRINT)
        prefs.remove(SettingsKeys.AUTO_BACKUP_LAST_FAILURE)
        prefs[SettingsKeys.AUTO_BACKUP_FAILURE_COUNT] = 0
        prefs[SettingsKeys.AUTO_BACKUP_FAILURE_NOTIFIED] = false
    }

    class Factory(
        private val dataStore: DataStore<Preferences>,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AutoBackupViewModel(dataStore, context) as T
    }

    private companion object {
        fun folderLabel(uriString: String): String =
            uriString.toUri().lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: uriString
    }
}
