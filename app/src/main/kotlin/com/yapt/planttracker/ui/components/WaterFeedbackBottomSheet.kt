package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.WateringFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaterFeedbackBottomSheet(
    plantName: String,
    title: String = stringResource(R.string.water_feedback_sheet_title, plantName),
    onDismiss: () -> Unit,
    onLog: (WateringFeedback?) -> Unit
) {
    // Single optional flag, not a 3-way chip group (#570, product ADR-0027); nothing pre-selected.
    var wasDry by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            FilterChip(
                selected = wasDry,
                onClick = { wasDry = !wasDry },
                label = { Text(stringResource(R.string.care_log_feedback_was_dry_label)) }
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onLog(if (wasDry) WateringFeedback.TOO_LATE else null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.quick_water_log))
            }
        }
    }
}
