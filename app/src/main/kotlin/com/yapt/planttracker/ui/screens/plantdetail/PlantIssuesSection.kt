package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.ui.theme.IssuePurple

/**
 * Always-visible "Active issues" section (#564) — currently-unresolved [PlantIssue]s, the ongoing
 * plant-health-status counterpart to [CustomRemindersCard]'s recurring tasks. Deliberately not gated
 * behind [com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry.PLANT_DETAIL_TABS], mirroring
 * that card's always-visible placement.
 */
@Composable
internal fun PlantIssuesCard(
    issues: List<PlantIssue>,
    customReminderNameById: Map<Long, String>,
    onReport: () -> Unit,
    onResolve: (PlantIssue) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.plant_issues_section),
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onReport) {
                    Icon(Icons.Filled.BugReport, contentDescription = stringResource(R.string.cd_report_plant_issue))
                }
            }
            if (issues.isEmpty()) {
                Text(
                    text = stringResource(R.string.plant_issues_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            } else {
                issues.forEach { issue ->
                    PlantIssueRow(
                        issue = issue,
                        linkedReminderName = issue.linkedReminderId?.let { customReminderNameById[it] },
                        onResolve = { onResolve(issue) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlantIssueRow(
    issue: PlantIssue,
    linkedReminderName: String?,
    onResolve: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = issue.name, style = MaterialTheme.typography.bodyLarge)
            val daysOngoing = CareSchedule.daysBetween(issue.startedAt, System.currentTimeMillis())
            Text(
                text = pluralStringResource(R.plurals.plant_issue_ongoing_days, daysOngoing, daysOngoing),
                style = MaterialTheme.typography.bodySmall,
                color = IssuePurple
            )
            if (linkedReminderName != null) {
                Text(
                    text = stringResource(R.string.plant_issue_linked_reminder, linkedReminderName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onResolve) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.cd_resolve_plant_issue, issue.name),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * "Report an issue" dialog (#564): free-text issue [PlantIssue.name] plus an optional "set a
 * treatment reminder" sub-section (toggle, defaulting the reminder name to the issue name) that
 * mirrors `CustomReminderDialog`'s plain-days interval field exactly. When the toggle is off,
 * [onConfirm] is called with null reminder fields and only a [PlantIssue] is created.
 */
@Composable
internal fun ReportIssueDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, reminderName: String?, reminderIntervalDays: Int?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var setReminder by remember { mutableStateOf(false) }
    var reminderName by remember { mutableStateOf("") }
    var reminderIntervalText by remember { mutableStateOf("7") }
    val parsedInterval = reminderIntervalText.toIntOrNull()?.takeIf { it >= 1 }
    val effectiveReminderName = reminderName.trim().ifEmpty { name.trim() }
    val reminderFieldsValid = effectiveReminderName.isNotBlank() && parsedInterval != null
    val canConfirm = name.isNotBlank() && (!setReminder || reminderFieldsValid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plant_issue_report_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.plant_issue_name_label)) },
                    placeholder = { Text(stringResource(R.string.plant_issue_name_placeholder)) },
                    singleLine = true
                )
                ReminderToggleFields(
                    state = ReminderToggleState(
                        setReminder = setReminder,
                        onSetReminderChange = { setReminder = it },
                        reminderName = reminderName,
                        onReminderNameChange = { reminderName = it },
                        reminderIntervalText = reminderIntervalText,
                        onReminderIntervalTextChange = { reminderIntervalText = it.filter(Char::isDigit) }
                    ),
                    issueName = name
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val reminderArgs = if (setReminder) effectiveReminderName to parsedInterval else null to null
                    onConfirm(name.trim(), reminderArgs.first, reminderArgs.second)
                },
                enabled = canConfirm
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * Bundles [ReminderToggleFields]' value/callback pairs into one parameter, mirroring
 * `CustomReminderActions`'s callback-bundling convention to stay under Detekt's `LongParameterList`
 * threshold.
 */
private data class ReminderToggleState(
    val setReminder: Boolean,
    val onSetReminderChange: (Boolean) -> Unit,
    val reminderName: String,
    val onReminderNameChange: (String) -> Unit,
    val reminderIntervalText: String,
    val onReminderIntervalTextChange: (String) -> Unit
)

/**
 * The optional "set a treatment reminder" toggle + fields inside [ReportIssueDialog], split out to
 * keep that composable under Detekt's `LongMethod` threshold.
 */
@Composable
private fun ReminderToggleFields(state: ReminderToggleState, issueName: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.plant_issue_set_reminder_toggle),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = state.setReminder, onCheckedChange = state.onSetReminderChange)
    }
    if (state.setReminder) {
        OutlinedTextField(
            value = state.reminderName,
            onValueChange = state.onReminderNameChange,
            label = { Text(stringResource(R.string.plant_issue_reminder_name_label)) },
            placeholder = { Text(issueName.ifBlank { stringResource(R.string.custom_reminder_name_placeholder) }) },
            singleLine = true
        )
        OutlinedTextField(
            value = state.reminderIntervalText,
            onValueChange = state.onReminderIntervalTextChange,
            label = { Text(stringResource(R.string.plant_issue_reminder_interval_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

/** Confirm dialog for resolving a [PlantIssue], with an optional free-text resolution note (#564). */
@Composable
internal fun ResolveIssueDialog(
    issue: PlantIssue,
    onDismiss: () -> Unit,
    onConfirm: (resolutionNote: String?) -> Unit
) {
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plant_issue_resolve_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.plant_issue_resolve_confirm, issue.name))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.plant_issue_resolution_note_label)) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(note.ifBlank { null }) }) {
                Text(stringResource(R.string.plant_issue_resolve_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
