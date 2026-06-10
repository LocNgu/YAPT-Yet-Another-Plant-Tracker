package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.yapt.planttracker.ui.util.emojiRes
import com.yapt.planttracker.ui.util.labelRes

/**
 * Bottom sheet shown when the user taps the quick-water button on a PlantCard.
 * Presents the three soil-state feedback chips (pre-selected to JUST_RIGHT) and
 * a single "Log watering" button. Dismissing without tapping "Log" cancels the action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickWaterBottomSheet(
    plantName: String,
    onLog: (WateringFeedback) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartialExpansion = true)
    var selectedFeedback by remember { mutableStateOf(WateringFeedback.JUST_RIGHT) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.quick_water_sheet_title, plantName),
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = stringResource(R.string.care_log_prompt_how_was_soil),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WateringFeedback.entries.forEach { feedback ->
                    FilterChip(
                        selected = selectedFeedback == feedback,
                        onClick = { selectedFeedback = feedback },
                        label = {
                            Text(
                                stringResource(
                                    R.string.feedback_label_format,
                                    stringResource(feedback.emojiRes()),
                                    stringResource(feedback.labelRes())
                                )
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = { onLog(selectedFeedback) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.quick_water_sheet_log_button))
            }
        }
    }
}
