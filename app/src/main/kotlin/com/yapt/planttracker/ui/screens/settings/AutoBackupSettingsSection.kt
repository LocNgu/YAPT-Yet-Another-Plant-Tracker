package com.yapt.planttracker.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.autobackup.AutoBackupFailureKind
import com.yapt.planttracker.domain.autobackup.AutoBackupFrequency
import com.yapt.planttracker.domain.autobackup.AutoBackupRetention
import com.yapt.planttracker.domain.autobackup.AutoBackupStatus
import com.yapt.planttracker.ui.components.yaptFilterChipColors
import java.text.DateFormat
import java.util.Date

@Composable
fun AutoBackupSettingsSection(viewModel: AutoBackupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::onFolderPicked)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        AutoBackupHeaderRow(state) { on ->
            if (on && !viewModel.hasFolder()) folderLauncher.launch(null) else viewModel.setEnabled(on)
        }
        if (state.enabled || state.folderLabel != null) {
            AutoBackupFolderRow(state.folderLabel) { folderLauncher.launch(null) }
        }
        if (state.enabled) AutoBackupOptions(state, viewModel)
    }
}

@Composable
private fun AutoBackupHeaderRow(state: AutoBackupUiState, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("auto_backup_switch")
            .toggleable(value = state.enabled, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.auto_backup_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                autoBackupStatusText(state.status),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.status.isProblem()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Switch(checked = state.enabled, onCheckedChange = null)
    }
}

@Composable
private fun AutoBackupFolderRow(folderLabel: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column {
            Text(stringResource(R.string.auto_backup_folder_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                folderLabel ?: stringResource(R.string.auto_backup_folder_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AutoBackupOptions(state: AutoBackupUiState, viewModel: AutoBackupViewModel) {
    ChipRow(stringResource(R.string.auto_backup_frequency_title)) {
        AutoBackupFrequency.entries.forEach { frequency ->
            FilterChip(
                selected = state.frequency == frequency,
                onClick = { viewModel.setFrequency(frequency) },
                label = { Text(stringResource(frequency.labelRes())) },
                colors = yaptFilterChipColors()
            )
        }
    }
    ChipRow(stringResource(R.string.auto_backup_retention_title)) {
        AutoBackupRetention.OPTIONS.forEach { keep ->
            FilterChip(
                selected = state.retention == keep,
                onClick = { viewModel.setRetention(keep) },
                label = { Text(pluralStringResource(R.plurals.auto_backup_retention_option, keep, keep)) },
                colors = yaptFilterChipColors()
            )
        }
    }
    CheckRow(stringResource(R.string.backup_include_photos), state.includePhotos, viewModel::setIncludePhotos)
    if (state.includePhotos) {
        CheckRow(stringResource(R.string.backup_optimize_photos), state.optimizePhotos, viewModel::setOptimizePhotos)
    }
}

@Composable
private fun ChipRow(title: String, chips: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            chips()
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun AutoBackupFrequency.labelRes(): Int = when (this) {
    AutoBackupFrequency.DAILY -> R.string.auto_backup_frequency_daily
    AutoBackupFrequency.WEEKLY -> R.string.auto_backup_frequency_weekly
}

private fun AutoBackupStatus.isProblem(): Boolean =
    this is AutoBackupStatus.FolderUnavailable || this is AutoBackupStatus.Failed

@Composable
private fun autoBackupStatusText(status: AutoBackupStatus): String = when (status) {
    AutoBackupStatus.Off -> stringResource(R.string.auto_backup_status_off)
    AutoBackupStatus.AwaitingFirstRun -> stringResource(R.string.auto_backup_status_waiting)
    AutoBackupStatus.FolderUnavailable -> stringResource(R.string.auto_backup_status_folder_unavailable)
    is AutoBackupStatus.Failed -> stringResource(
        if (status.kind == AutoBackupFailureKind.STORAGE) {
            R.string.auto_backup_status_failed_storage
        } else {
            R.string.auto_backup_status_failed
        }
    )
    is AutoBackupStatus.Success -> {
        val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(status.at))
        if (status.skippedPhotos > 0) {
            pluralStringResource(
                R.plurals.auto_backup_status_success_skipped,
                status.skippedPhotos,
                whenText,
                status.skippedPhotos
            )
        } else {
            stringResource(R.string.auto_backup_status_success, whenText)
        }
    }
}
