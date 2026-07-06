package com.yapt.planttracker.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yapt.planttracker.R

/**
 * Reminder shown when a plant hasn't been photographed in a while. Shared by PlantDetailScreen
 * and PlantListScreen so the two entry points stay visually identical.
 *
 * @param daysSince days since the plant's most recent photo (or since creation if it has none)
 * @param onTakePhoto invoked when the user chooses to take a photo
 * @param onDismiss invoked when the user dismisses the reminder
 */
@Composable
fun PhotoReminderDialog(
    daysSince: Int,
    onTakePhoto: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.photo_reminder_dialog_title)) },
        text = { Text(stringResource(R.string.photo_reminder_dialog_text, daysSince)) },
        confirmButton = {
            TextButton(onClick = onTakePhoto) {
                Text(stringResource(R.string.photo_source_take_photo))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dismiss))
            }
        }
    )
}
