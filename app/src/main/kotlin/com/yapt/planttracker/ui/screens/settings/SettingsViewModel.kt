package com.yapt.planttracker.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.R
import com.yapt.planttracker.data.backup.BackupManager
import com.yapt.planttracker.data.backup.BackupManagerInterface
import com.yapt.planttracker.data.backup.BackupResult
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsDefaults
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.devmode.DemoDataSeeder
import com.yapt.planttracker.domain.featureflag.FeatureFlag
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.notification.NotificationPermission
import com.yapt.planttracker.ui.theme.ThemeMode
import com.yapt.planttracker.worker.ReminderScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val dataStore: DataStore<Preferences>,
    private val context: Context,
    private val database: PlantDatabase,
    private val plantRepository: PlantRepository,
    private val featureFlags: FeatureFlags = FeatureFlags(dataStore),
    private val backupManager: BackupManagerInterface = BackupManager(context, database, dataStore)
) : ViewModel() {

    val flags: List<FeatureFlag> get() = featureFlags.flags

    // Not a constructor parameter: DemoDataSeeder only needs dependencies already available on
    // this class (plantRepository, database), and adding it as a 7th constructor parameter would
    // trip Detekt's LongParameterList.constructorThreshold — the same tradeoff #521 made for the
    // feature-flag list (see ADR-0022's "Deliberate deviation from #521 AC10" section).
    private val demoDataSeeder: DemoDataSeeder by lazy {
        DemoDataSeeder(plantRepository, CareLogRepository(database.careLogDao()), database)
    }

    val notificationsEnabled: StateFlow<Boolean> = dataStore.data
        .map { it[SettingsKeys.NOTIFICATIONS_ENABLED] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val keepScreenOn: StateFlow<Boolean> = dataStore.data
        .map { it[SettingsKeys.KEEP_SCREEN_ON] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val photoReminderEnabled: StateFlow<Boolean> = dataStore.data
        .map { it[SettingsKeys.PHOTO_REMINDER_ENABLED] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val combineNotifications: StateFlow<Boolean> = dataStore.data
        .map { it[SettingsKeys.COMBINE_NOTIFICATIONS] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val fertilizingNotificationsEnabled: StateFlow<Boolean> = dataStore.data
        .map { it[SettingsKeys.FERTILIZING_NOTIFICATIONS_ENABLED] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val reminderHour: StateFlow<Int> = dataStore.data
        .map { it[SettingsKeys.REMINDER_HOUR] ?: SettingsDefaults.REMINDER_HOUR }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 9)

    val reminderMinute: StateFlow<Int> = dataStore.data
        .map { it[SettingsKeys.REMINDER_MINUTE] ?: SettingsDefaults.REMINDER_MINUTE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val themeMode: StateFlow<ThemeMode> = dataStore.data
        .map { prefs ->
            runCatching { ThemeMode.valueOf(prefs[SettingsKeys.THEME_MODE] ?: "") }
                .getOrDefault(ThemeMode.SYSTEM)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val seasonalAmplitude: StateFlow<SeasonalAmplitude> = dataStore.data
        .map { prefs ->
            runCatching { SeasonalAmplitude.valueOf(prefs[SettingsKeys.SEASONAL_AMPLITUDE] ?: "") }
                .getOrDefault(SeasonalAmplitude.STANDARD)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SeasonalAmplitude.STANDARD)

    /** "Ask before changing intervals" (#572). */
    val askBeforeChangingIntervals: StateFlow<Boolean> = dataStore.data
        .map { it[SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val graveyardCount: StateFlow<Int> = plantRepository.getArchivedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val developerModeEnabled: StateFlow<Boolean> = dataStore.data
        .map { it[SettingsKeys.DEVELOPER_MODE_ENABLED] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val featureFlagStates: StateFlow<Map<String, Boolean>> = if (flags.isEmpty()) {
        MutableStateFlow(emptyMap())
    } else {
        combine(flags.map { flag -> featureFlags.isEnabled(flag).map { flag.key to it } }) { pairs -> pairs.toMap() }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                flags.associate { it.key to it.default }
            )
    }

    private val _backupResult = MutableSharedFlow<BackupResult>()
    val backupResult: SharedFlow<BackupResult> = _backupResult.asSharedFlow()

    private val _isBackupInProgress = MutableStateFlow(false)
    val isBackupInProgress: StateFlow<Boolean> = _isBackupInProgress.asStateFlow()

    private val _debugActionEvent = MutableSharedFlow<String>()
    val debugActionEvent: SharedFlow<String> = _debugActionEvent.asSharedFlow()

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[SettingsKeys.KEEP_SCREEN_ON] = enabled }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            dataStore.edit { it[SettingsKeys.THEME_MODE] = mode.name }
        }
    }

    fun setSeasonalAmplitude(amplitude: SeasonalAmplitude) {
        viewModelScope.launch {
            dataStore.edit { it[SettingsKeys.SEASONAL_AMPLITUDE] = amplitude.name }
        }
    }

    fun setAskBeforeChangingIntervals(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS] = enabled }
        }
    }

    fun setPhotoReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[SettingsKeys.PHOTO_REMINDER_ENABLED] = enabled }
        }
    }

    fun setCombineNotifications(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[SettingsKeys.COMBINE_NOTIFICATIONS] = enabled }
        }
    }

    fun setFertilizingNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[SettingsKeys.FERTILIZING_NOTIFICATIONS_ENABLED] = enabled }
        }
    }

    fun setDeveloperModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[SettingsKeys.DEVELOPER_MODE_ENABLED] = enabled }
            // Turning developer mode off resets every flag to its registry default (product
            // ADR-0022) so "developer mode off" always means a stock build with no hidden state.
            if (!enabled && featureFlags.flags.isNotEmpty()) {
                featureFlags.resetAll()
            }
        }
    }

    fun setFlagEnabled(flag: FeatureFlag, enabled: Boolean) {
        viewModelScope.launch {
            featureFlags.setEnabled(flag, enabled)
        }
    }

    fun resetWhatsNewSeenState() {
        viewModelScope.launch {
            dataStore.edit { it.remove(SettingsKeys.LAST_SEEN_VERSION_CODE) }
            _debugActionEvent.emit(context.getString(R.string.dev_mode_reset_whats_new_snackbar))
        }
    }

    fun runReminderCheckNow() {
        viewModelScope.launch {
            if (NotificationPermission.isGranted(context)) {
                ReminderScheduler.runNow(context)
                _debugActionEvent.emit(context.getString(R.string.dev_mode_run_reminder_check_snackbar))
            } else {
                _debugActionEvent.emit(context.getString(R.string.dev_mode_run_reminder_check_denied_snackbar))
            }
        }
    }

    fun seedDemoPlants() {
        viewModelScope.launch {
            val count = demoDataSeeder.seed()
            _debugActionEvent.emit(
                context.resources.getQuantityString(R.plurals.dev_mode_seed_demo_result, count, count)
            )
        }
    }

    fun removeDemoPlants() {
        viewModelScope.launch {
            val count = demoDataSeeder.remove()
            val message = if (count == 0) {
                context.getString(R.string.dev_mode_remove_demo_none)
            } else {
                context.resources.getQuantityString(R.plurals.dev_mode_remove_demo_result, count, count)
            }
            _debugActionEvent.emit(message)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[SettingsKeys.NOTIFICATIONS_ENABLED] = enabled }
            if (enabled) {
                val prefs = dataStore.data.first()
                val hour = prefs[SettingsKeys.REMINDER_HOUR] ?: SettingsDefaults.REMINDER_HOUR
                val minute = prefs[SettingsKeys.REMINDER_MINUTE] ?: SettingsDefaults.REMINDER_MINUTE
                ReminderScheduler.schedule(context, hour, minute)
            } else {
                ReminderScheduler.cancel(context)
            }
        }
    }

    fun setReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            dataStore.edit {
                it[SettingsKeys.REMINDER_HOUR] = hour
                it[SettingsKeys.REMINDER_MINUTE] = minute
            }
            val enabled = dataStore.data.first()[SettingsKeys.NOTIFICATIONS_ENABLED] ?: true
            if (enabled) {
                ReminderScheduler.schedule(context, hour, minute)
            }
        }
    }

    fun exportBackup(uri: Uri, includePhotos: Boolean) {
        _isBackupInProgress.value = true
        viewModelScope.launch {
            val result = backupManager.exportBackup(uri, includePhotos)
            _backupResult.emit(result)
            _isBackupInProgress.value = false
        }
    }

    fun importBackup(uri: Uri) {
        _isBackupInProgress.value = true
        viewModelScope.launch {
            val result = backupManager.importBackup(uri)
            _backupResult.emit(result)
            // Keep true while the FutureSchemaWarning dialog is shown; reset in dismiss/proceed.
            if (result !is BackupResult.FutureSchemaWarning) {
                _isBackupInProgress.value = false
            }
        }
    }

    fun proceedWithFutureSchemaImport(onProceed: suspend () -> BackupResult) {
        _isBackupInProgress.value = true
        viewModelScope.launch {
            val result = runCatching { onProceed() }.getOrElse { e ->
                BackupResult.Error(e.message ?: "Import failed")
            }
            _backupResult.emit(result)
            _isBackupInProgress.value = false
        }
    }

    fun dismissFutureSchemaImport(onDismiss: suspend () -> Unit) {
        viewModelScope.launch {
            onDismiss()
            _isBackupInProgress.value = false
        }
    }

    class Factory(
        private val dataStore: DataStore<Preferences>,
        private val context: Context,
        private val database: PlantDatabase,
        private val plantRepository: PlantRepository,
        private val featureFlags: FeatureFlags
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(dataStore, context, database, plantRepository, featureFlags) as T
    }
}
