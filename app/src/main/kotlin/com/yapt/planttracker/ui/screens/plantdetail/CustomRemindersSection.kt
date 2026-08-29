@file:Suppress("MatchingDeclarationName")

package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.CustomReminderStatus
import com.yapt.planttracker.ui.theme.OverdueRed
import com.yapt.planttracker.ui.theme.WarnOrange
import com.yapt.planttracker.util.DateUtils

/**
 * Bundles the [CustomRemindersCard]/[CustomReminderRow] callbacks into one parameter so neither
 * composable's parameter list trips Detekt's `LongParameterList` (mirrors [IntervalSetting] bundling
 * a config struct for the same reason).
 */
internal data class CustomReminderActions(
    val onAdd: () -> Unit,
    val onEdit: (CustomReminder) -> Unit,
    val onDelete: (CustomReminder) -> Unit,
    val onMarkDone: (CustomReminder) -> Unit
)

/**
 * Always-visible "Custom reminders" section (#232) — unbounded, free-text recurring reminders per
 * plant, deliberately not gated behind [com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry.PLANT_DETAIL_TABS]
 * unlike the per-action tabs below it. Lives in its own file (not `PlantDetailScreen.kt`), mirroring
 * `PlantIssuesSection.kt`, to stay under Detekt's per-file `TooManyFunctions` threshold.
 */
@Composable
internal fun CustomRemindersCard(
    reminders: List<CustomReminder>,
    statuses: List<CustomReminderStatus>,
    actions: CustomReminderActions,
    modifier: Modifier = Modifier
) {
    val statusById = remember(statuses) { statuses.associateBy { it.reminder.id } }
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
                    text = stringResource(R.string.custom_reminders_section),
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = actions.onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_custom_reminder))
                }
            }
            if (reminders.isEmpty()) {
                Text(
                    text = stringResource(R.string.custom_reminders_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            } else {
                reminders.forEach { reminder ->
                    CustomReminderRow(
                        reminder = reminder,
                        status = statusById[reminder.id],
                        actions = actions
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomReminderRow(
    reminder: CustomReminder,
    status: CustomReminderStatus?,
    actions: CustomReminderActions,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = reminder.name, style = MaterialTheme.typography.bodyLarge)
            val intervalText = pluralStringResource(
                R.plurals.custom_reminder_interval_summary,
                reminder.intervalDays,
                reminder.intervalDays
            )
            val countdown = status?.nextDueAt?.let { DateUtils.formatCountdown(it) }
            val statusColor = when {
                status?.isOverdue == true -> OverdueRed
                status?.isDueSoon == true -> WarnOrange
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = if (countdown != null) "$intervalText · $countdown" else intervalText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )
        }
        IconButton(onClick = { actions.onMarkDone(reminder) }) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.cd_mark_custom_reminder_done, reminder.name),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = { actions.onEdit(reminder) }) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.cd_edit_custom_reminder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { actions.onDelete(reminder) }) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.cd_delete_custom_reminder),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Add/edit dialog for a single [CustomReminder]. [initial] `null` means "add"; non-null pre-fills
 * the fields for editing. Plain-days interval only — no months toggle (unlike repotting), since
 * disease/treatment cadences are day-scale (issue #232 spec clarifications).
 */
@Composable
internal fun CustomReminderDialog(
    initial: CustomReminder?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, intervalDays: Int) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var intervalText by remember(initial) { mutableStateOf((initial?.intervalDays ?: 7).toString()) }
    val parsedInterval = intervalText.toIntOrNull()?.takeIf { it >= 1 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial != null) R.string.custom_reminder_edit_title else R.string.custom_reminder_add_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.custom_reminder_name_label)) },
                    placeholder = { Text(stringResource(R.string.custom_reminder_name_placeholder)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { intervalText = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.custom_reminder_interval_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsedInterval?.let { onConfirm(name.trim(), it) } },
                enabled = name.isNotBlank() && parsedInterval != null
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
