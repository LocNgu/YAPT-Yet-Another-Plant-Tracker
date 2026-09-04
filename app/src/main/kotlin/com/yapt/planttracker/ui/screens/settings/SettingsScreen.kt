package com.yapt.planttracker.ui.screens.settings

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.BuildConfig
import com.yapt.planttracker.R
import com.yapt.planttracker.data.backup.BackupResult
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.domain.devmode.DeveloperModeTapOutcome
import com.yapt.planttracker.domain.devmode.DeveloperModeUnlock
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.ui.components.SeasonalWateringCurveChart
import com.yapt.planttracker.ui.theme.ThemeMode
import com.yapt.planttracker.ui.util.labelRes
import com.yapt.planttracker.util.DateUtils
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onRestoreSuccess: (plantCount: Int, logCount: Int) -> Unit,
    onShowWhatsNew: () -> Unit,
    onNavigateToGraveyard: () -> Unit = {}
) {
    val context = LocalContext.current
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
    }
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val photoReminderEnabled by viewModel.photoReminderEnabled.collectAsStateWithLifecycle()
    val combineNotifications by viewModel.combineNotifications.collectAsStateWithLifecycle()
    val fertilizingNotificationsEnabled by viewModel.fertilizingNotificationsEnabled.collectAsStateWithLifecycle()
    val graveyardCount by viewModel.graveyardCount.collectAsStateWithLifecycle()
    val reminderHour by viewModel.reminderHour.collectAsStateWithLifecycle()
    val reminderMinute by viewModel.reminderMinute.collectAsStateWithLifecycle()
    val isBackupInProgress by viewModel.isBackupInProgress.collectAsStateWithLifecycle()
    val developerModeEnabled by viewModel.developerModeEnabled.collectAsStateWithLifecycle()
    val featureFlagStates by viewModel.featureFlagStates.collectAsStateWithLifecycle()
    val seasonalAmplitude by viewModel.seasonalAmplitude.collectAsStateWithLifecycle()
    val seasonalWateringEnabled = featureFlagStates[FeatureFlagRegistry.SEASONAL_WATERING.key]
        ?: FeatureFlagRegistry.SEASONAL_WATERING.default
    val askBeforeChangingIntervals by viewModel.askBeforeChangingIntervals.collectAsStateWithLifecycle()
    val adaptiveWateringEnabled = featureFlagStates[FeatureFlagRegistry.ADAPTIVE_WATERING.key]
        ?: FeatureFlagRegistry.ADAPTIVE_WATERING.default

    BackHandler(enabled = isBackupInProgress) { /* consume back press while operation is running */ }
    var showTimePicker by remember { mutableStateOf(false) }
    // Screen-scoped: a fresh remember{} on every entry into Settings, so leaving the screen
    // and returning always starts the countdown over (#520 AC3 — no wall-clock timeout).
    var versionTapCount by remember { mutableIntStateOf(0) }
    // Set on the unlocking tap so the LaunchedEffect below scrolls the newly revealed
    // Developer section into view. Not set when Settings simply opens with it already on.
    var justUnlockedDeveloperMode by remember { mutableStateOf(false) }
    val settingsScrollState = rememberScrollState()
    val versionRowClickLabel = stringResource(R.string.cd_version_row_show_version)
    val devModeEnabledMessage = stringResource(R.string.dev_mode_enabled_snackbar)
    val devModeDisabledMessage = stringResource(R.string.dev_mode_disabled_snackbar)

    // The Developer section renders at the very bottom of Settings, so on unlock it would
    // otherwise appear off-screen with no indication anything happened.
    LaunchedEffect(developerModeEnabled) {
        if (developerModeEnabled && justUnlockedDeveloperMode) {
            justUnlockedDeveloperMode = false
            withFrameNanos { } // let the newly added section be laid out before measuring
            settingsScrollState.animateScrollTo(settingsScrollState.maxValue)
        }
    }
    val timePickerState = key(reminderHour, reminderMinute) {
        rememberTimePickerState(
            initialHour = reminderHour,
            initialMinute = reminderMinute,
            is24Hour = true
        )
    }

    var showExportDialog by remember { mutableStateOf(false) }
    var includePhotos by remember { mutableStateOf(true) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showFutureSchemaDialog by remember { mutableStateOf(false) }
    var futureSchemaVersion by remember { mutableStateOf(0) }
    var pendingFutureSchemaImport by remember { mutableStateOf<(suspend () -> BackupResult)?>(null) }
    var pendingFutureSchemaOnDismiss by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    var showSeedDemoConfirmDialog by remember { mutableStateOf(false) }
    var showRemoveDemoConfirmDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    // Single ordered stream every snackbar-producing source emits into, collected by exactly one
    // LaunchedEffect below. Prevents the uncoordinated dismiss()+showSnackbar() races that used
    // to happen when multiple coroutines wrote to snackbarHostState directly (#522). A newer
    // message REPLACES whatever is currently showing rather than queuing behind it — the buffer
    // keeps only the latest not-yet-collected message, and the collector below uses
    // collectLatest so an already-showing snackbar is cancelled rather than waited out.
    val snackbarMessages = remember {
        MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }
    val exportSuccessFormat = stringResource(R.string.backup_export_success)
    val errorFormat = stringResource(R.string.backup_error)

    val todayString = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { viewModel.exportBackup(it, includePhotos) }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            pendingRestoreUri = it
            showRestoreConfirmDialog = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.debugActionEvent.collect { message -> snackbarMessages.tryEmit(message) }
    }

    LaunchedEffect(Unit) {
        viewModel.backupResult.collect { result ->
            when (result) {
                is BackupResult.ExportSuccess ->
                    snackbarMessages.tryEmit(
                        String.format(exportSuccessFormat, result.plantCount, result.logCount)
                    )
                is BackupResult.ImportSuccess ->
                    onRestoreSuccess(result.plantCount, result.logCount)
                is BackupResult.FutureSchemaWarning -> {
                    futureSchemaVersion = result.schemaVersion
                    pendingFutureSchemaImport = result.onProceed
                    pendingFutureSchemaOnDismiss = result.onDismiss
                    showFutureSchemaDialog = true
                }
                is BackupResult.Error ->
                    snackbarMessages.tryEmit(String.format(errorFormat, result.message))
            }
        }
    }

    // The one and only collector allowed to touch snackbarHostState directly — every other
    // snackbar-producing source above/below emits into snackbarMessages instead.
    //
    // collectLatest, not collect: showSnackbar() suspends until its snackbar is dismissed or
    // times out, so a plain collect would sit parked inside it and only reach the next message
    // seconds later — turning "replace" into "queue" and making a debug action's confirmation
    // appear behind stale unlock-countdown text. collectLatest cancels the in-flight
    // showSnackbar when a newer message arrives, which removes the current snackbar, so no
    // explicit dismiss() is needed.
    LaunchedEffect(Unit) {
        snackbarMessages.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.backup_export_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.backup_export_dialog_text))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includePhotos = !includePhotos }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = includePhotos,
                            onCheckedChange = { includePhotos = it }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.backup_include_photos))
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showExportDialog = false
                    createDocumentLauncher.launch("yapt-backup-$todayString.yapt")
                }) { Text(stringResource(R.string.backup_export_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showRestoreConfirmDialog = false
                pendingRestoreUri = null
            },
            title = { Text(stringResource(R.string.backup_restore_dialog_title)) },
            text = { Text(stringResource(R.string.backup_restore_dialog_text)) },
            confirmButton = {
                Button(onClick = {
                    showRestoreConfirmDialog = false
                    pendingRestoreUri?.let { viewModel.importBackup(it) }
                    pendingRestoreUri = null
                }) { Text(stringResource(R.string.backup_restore_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreConfirmDialog = false
                    pendingRestoreUri = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showFutureSchemaDialog) {
        AlertDialog(
            onDismissRequest = {
                showFutureSchemaDialog = false
                pendingFutureSchemaOnDismiss?.let { viewModel.dismissFutureSchemaImport(it) }
                pendingFutureSchemaImport = null
                pendingFutureSchemaOnDismiss = null
            },
            title = { Text(stringResource(R.string.backup_future_schema_title)) },
            text = {
                Text(stringResource(R.string.backup_future_schema_text, futureSchemaVersion))
            },
            confirmButton = {
                Button(onClick = {
                    showFutureSchemaDialog = false
                    pendingFutureSchemaImport?.let { viewModel.proceedWithFutureSchemaImport(it) }
                    pendingFutureSchemaImport = null
                    pendingFutureSchemaOnDismiss = null
                }) { Text(stringResource(R.string.backup_proceed_button)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFutureSchemaDialog = false
                    pendingFutureSchemaOnDismiss?.let { viewModel.dismissFutureSchemaImport(it) }
                    pendingFutureSchemaImport = null
                    pendingFutureSchemaOnDismiss = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.reminder_time_dialog_title)) },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setReminderTime(timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showSeedDemoConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSeedDemoConfirmDialog = false },
            title = { Text(stringResource(R.string.dev_mode_seed_demo_confirm_title)) },
            text = { Text(stringResource(R.string.dev_mode_seed_demo_confirm_text)) },
            confirmButton = {
                Button(onClick = {
                    showSeedDemoConfirmDialog = false
                    viewModel.seedDemoPlants()
                }) { Text(stringResource(R.string.dev_mode_seed_demo_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showSeedDemoConfirmDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showRemoveDemoConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDemoConfirmDialog = false },
            title = { Text(stringResource(R.string.dev_mode_remove_demo_confirm_title)) },
            text = { Text(stringResource(R.string.dev_mode_remove_demo_confirm_text)) },
            confirmButton = {
                Button(onClick = {
                    showRemoveDemoConfirmDialog = false
                    viewModel.removeDemoPlants()
                }) { Text(stringResource(R.string.dev_mode_remove_demo_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDemoConfirmDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (isBackupInProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.backup_in_progress_title)) },
            text = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {}
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !isBackupInProgress && !showFutureSchemaDialog) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(settingsScrollState)
        ) {
            Text(
                text = stringResource(R.string.settings_section_appearance),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            val themeOptions = listOf(ThemeMode.LIGHT, ThemeMode.SYSTEM, ThemeMode.DARK)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                themeOptions.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = mode == themeMode,
                        onClick = { viewModel.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = themeOptions.size)
                    ) {
                        Text(stringResource(mode.labelRes()))
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.settings_section_reminders),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            SettingsItemRow(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.notifications_enabled),
                subtitle = stringResource(R.string.settings_notifications_subtitle),
                trailingContent = {
                    Switch(
                        modifier = Modifier.testTag("notifications_enabled_switch"),
                        checked = notificationsEnabled,
                        onCheckedChange = {
                            viewModel.setNotificationsEnabled(it)
                            if (!it) showTimePicker = false
                        }
                    )
                }
            )

            if (notificationsEnabled) {
                SettingsItemRow(
                    icon = Icons.Filled.Schedule,
                    title = stringResource(R.string.reminder_time),
                    subtitle = DateUtils.formatHourMinute(reminderHour, reminderMinute),
                    subtitleColor = MaterialTheme.colorScheme.primary,
                    onClick = { showTimePicker = true }
                )

                SettingsItemRow(
                    icon = Icons.Filled.Spa,
                    title = stringResource(R.string.fertilizing_notifications_title),
                    subtitle = stringResource(R.string.fertilizing_notifications_subtitle),
                    trailingContent = {
                        Switch(
                            modifier = Modifier.testTag("fertilizing_notifications_switch"),
                            checked = fertilizingNotificationsEnabled,
                            onCheckedChange = { viewModel.setFertilizingNotificationsEnabled(it) }
                        )
                    }
                )

                SettingsItemRow(
                    icon = Icons.Filled.Layers,
                    title = stringResource(R.string.combine_notifications_title),
                    subtitle = stringResource(R.string.combine_notifications_subtitle),
                    trailingContent = {
                        Switch(
                            modifier = Modifier.testTag("combine_notifications_switch"),
                            checked = combineNotifications,
                            onCheckedChange = { viewModel.setCombineNotifications(it) }
                        )
                    }
                )
            }

            SettingsItemRow(
                icon = Icons.Filled.CameraAlt,
                title = stringResource(R.string.photo_reminder_setting_title),
                subtitle = stringResource(R.string.photo_reminder_setting_subtitle),
                trailingContent = {
                    Switch(
                        checked = photoReminderEnabled,
                        onCheckedChange = { viewModel.setPhotoReminderEnabled(it) }
                    )
                }
            )

            if (seasonalWateringEnabled) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = stringResource(R.string.settings_section_seasonal_watering),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )

                Text(
                    text = stringResource(R.string.seasonal_amplitude_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                val amplitudeOptions = listOf(
                    SeasonalAmplitude.OFF,
                    SeasonalAmplitude.MILD,
                    SeasonalAmplitude.STANDARD,
                    SeasonalAmplitude.STRONG
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    amplitudeOptions.forEachIndexed { index, amplitude ->
                        SegmentedButton(
                            selected = amplitude == seasonalAmplitude,
                            onClick = { viewModel.setSeasonalAmplitude(amplitude) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = amplitudeOptions.size),
                            modifier = Modifier.testTag("seasonal_amplitude_option_${amplitude.name}")
                        ) {
                            Text(stringResource(amplitude.labelRes()))
                        }
                    }
                }

                val hemisphere = remember { SeasonalWatering.currentHemisphere() }
                SeasonalWateringCurveChart(
                    amplitude = seasonalAmplitude.value,
                    hemisphere = hemisphere,
                    showHemisphereCaption = true,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (adaptiveWateringEnabled) {
                SettingsItemRow(
                    icon = Icons.Filled.AutoAwesome,
                    title = stringResource(R.string.ask_before_changing_intervals_title),
                    subtitle = stringResource(R.string.ask_before_changing_intervals_subtitle),
                    trailingContent = {
                        Switch(
                            modifier = Modifier.testTag("ask_before_changing_intervals_switch"),
                            checked = askBeforeChangingIntervals,
                            onCheckedChange = { viewModel.setAskBeforeChangingIntervals(it) }
                        )
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.settings_section_display),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            SettingsItemRow(
                icon = Icons.Filled.BrightnessMedium,
                title = stringResource(R.string.keep_screen_on),
                subtitle = stringResource(R.string.keep_screen_on_subtitle),
                trailingContent = {
                    Switch(
                        modifier = Modifier.testTag("keep_screen_on_switch"),
                        checked = keepScreenOn,
                        onCheckedChange = { viewModel.setKeepScreenOn(it) }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.backup_section_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            SettingsItemRow(
                icon = Icons.Filled.CloudUpload,
                title = stringResource(R.string.backup_export_item_title),
                subtitle = stringResource(R.string.backup_export_item_subtitle),
                onClick = if (isBackupInProgress) null else ({ showExportDialog = true })
            )

            SettingsItemRow(
                icon = Icons.Filled.CloudDownload,
                title = stringResource(R.string.backup_restore_item_title),
                subtitle = stringResource(R.string.backup_restore_item_subtitle),
                onClick = if (isBackupInProgress) {
                    null
                } else {
                    (
                        {
                            openDocumentLauncher.launch(
                                arrayOf("application/octet-stream", "*/*")
                            )
                        }
                        )
                }
            )

            SettingsItemRow(
                icon = Icons.Filled.DeleteSweep,
                title = stringResource(R.string.graveyard_settings_title),
                subtitle = pluralStringResource(R.plurals.graveyard_settings_subtitle, graveyardCount, graveyardCount),
                onClick = onNavigateToGraveyard
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.settings_section_about),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = versionRowClickLabel, role = Role.Button) {
                        val result = DeveloperModeUnlock.registerTap(versionTapCount, developerModeEnabled)
                        versionTapCount = result.newTapCount
                        when (val outcome = result.outcome) {
                            // Replace rather than queue: a newer message supersedes whatever the
                            // single collector is currently showing, so a fast tapper never reads
                            // a stale "N taps away" well after unlocking.
                            is DeveloperModeTapOutcome.Countdown -> snackbarMessages.tryEmit(
                                context.resources.getQuantityString(
                                    R.plurals.dev_mode_countdown_taps_away,
                                    outcome.tapsRemaining,
                                    outcome.tapsRemaining
                                )
                            )
                            DeveloperModeTapOutcome.Unlocked -> {
                                justUnlockedDeveloperMode = true
                                viewModel.setDeveloperModeEnabled(true)
                                snackbarMessages.tryEmit(devModeEnabledMessage)
                            }
                            DeveloperModeTapOutcome.Silent, DeveloperModeTapOutcome.Inert -> Unit
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("settings_about_version_row")
            ) {
                Column {
                    Text(
                        stringResource(R.string.settings_about_app_name),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.settings_about_version, versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SettingsItemRow(
                icon = Icons.Filled.AutoAwesome,
                title = stringResource(R.string.settings_whats_new_title),
                subtitle = stringResource(R.string.settings_whats_new_subtitle),
                onClick = { onShowWhatsNew() }
            )

            if (developerModeEnabled) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = stringResource(R.string.dev_mode_section_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )

                SettingsItemRow(
                    icon = Icons.Filled.Code,
                    title = stringResource(R.string.dev_mode_master_switch_title),
                    subtitle = stringResource(R.string.dev_mode_master_switch_subtitle),
                    trailingContent = {
                        Switch(
                            modifier = Modifier.testTag("developer_mode_switch"),
                            checked = developerModeEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setDeveloperModeEnabled(enabled)
                                if (!enabled) {
                                    snackbarMessages.tryEmit(devModeDisabledMessage)
                                }
                            }
                        )
                    }
                )

                SettingsItemRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.dev_mode_build_info_version_title),
                    subtitle = stringResource(
                        R.string.dev_mode_build_info_version_value,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE
                    )
                )

                SettingsItemRow(
                    icon = Icons.Filled.BugReport,
                    title = stringResource(R.string.dev_mode_build_info_build_type_title),
                    subtitle = BuildConfig.BUILD_TYPE
                )

                SettingsItemRow(
                    icon = Icons.Filled.Storage,
                    title = stringResource(R.string.dev_mode_build_info_db_version_title),
                    subtitle = PlantDatabase.DB_VERSION.toString()
                )

                SettingsItemRow(
                    icon = Icons.Filled.PhoneAndroid,
                    title = stringResource(R.string.dev_mode_build_info_api_level_title),
                    subtitle = Build.VERSION.SDK_INT.toString()
                )

                Text(
                    text = stringResource(R.string.dev_mode_feature_flags_section_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )

                if (viewModel.flags.isEmpty()) {
                    Text(
                        text = stringResource(R.string.dev_mode_feature_flags_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                } else {
                    viewModel.flags.forEach { flag ->
                        SettingsItemRow(
                            icon = Icons.Filled.Flag,
                            title = stringResource(flag.titleRes),
                            subtitle = stringResource(flag.descriptionRes),
                            trailingContent = {
                                Switch(
                                    modifier = Modifier.testTag("feature_flag_switch_${flag.key}"),
                                    checked = featureFlagStates[flag.key] ?: flag.default,
                                    onCheckedChange = { enabled -> viewModel.setFlagEnabled(flag, enabled) }
                                )
                            }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.dev_mode_debug_actions_section_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )

                SettingsItemRow(
                    icon = Icons.Filled.Restore,
                    title = stringResource(R.string.dev_mode_action_reset_whats_new_title),
                    subtitle = stringResource(R.string.dev_mode_action_reset_whats_new_subtitle),
                    modifier = Modifier.testTag("dev_mode_reset_whats_new_row"),
                    onClick = { viewModel.resetWhatsNewSeenState() }
                )

                SettingsItemRow(
                    icon = Icons.Filled.NotificationsActive,
                    title = stringResource(R.string.dev_mode_action_run_reminder_check_title),
                    subtitle = stringResource(R.string.dev_mode_action_run_reminder_check_subtitle),
                    modifier = Modifier.testTag("dev_mode_run_reminder_check_row"),
                    onClick = { viewModel.runReminderCheckNow() }
                )

                SettingsItemRow(
                    icon = Icons.Filled.Eco,
                    title = stringResource(R.string.dev_mode_action_seed_demo_title),
                    subtitle = stringResource(R.string.dev_mode_action_seed_demo_subtitle),
                    modifier = Modifier.testTag("dev_mode_seed_demo_row"),
                    onClick = { showSeedDemoConfirmDialog = true }
                )

                SettingsItemRow(
                    icon = Icons.Filled.DeleteForever,
                    title = stringResource(R.string.dev_mode_action_remove_demo_title),
                    subtitle = stringResource(R.string.dev_mode_action_remove_demo_subtitle),
                    modifier = Modifier.testTag("dev_mode_remove_demo_row"),
                    onClick = { showRemoveDemoConfirmDialog = true }
                )
            }
        }
    }
}

@Composable
private fun SettingsItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor
            )
        }
        trailingContent?.invoke()
    }
}
