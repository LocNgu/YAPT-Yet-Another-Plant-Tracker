package com.yapt.planttracker.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yapt.planttracker.R

/** Foreground presentation of the post-watering standing-water reminder (#519). */
@Composable
fun PostWateringReminderDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.post_watering_notification_title)) },
        text = { Text(stringResource(R.string.post_watering_notification_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dismiss))
            }
        }
    )
}
