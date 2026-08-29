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
import androidx.compose.material3.OutlinedButton
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
import com.yapt.planttracker.domain.model.RescheduleReason
import com.yapt.planttracker.domain.model.WateringReason
import com.yapt.planttracker.ui.util.labelRes

/**
 * The off-schedule watering reason prompt (#586, product ADR-0030), replacing #570's single
 * "was dry" flag on the same bottom sheet. Only ever shown when
 * [com.yapt.planttracker.domain.model.PlantCareStatus.isWateringOnSchedule] is false — an on-schedule
 * watering is logged straight away, which is what keeps the quick-log surfaces' fast path.
 *
 * Two chips, nothing preselected, each deselectable (product ADR-0024). Tapping **Log** without
 * choosing is the explicit "I'd rather not say" answer: the watering is logged and the adaptive model
 * excludes it from base learning, the same as [WateringReason.JUST_MY_TIMING]. Dismissing the sheet
 * cancels the watering outright, so either way nothing wrong reaches the model.
 *
 * [gapRanLong] ([com.yapt.planttracker.domain.model.PlantCareStatus.isWateringGapLong]) selects the
 * late wording for both the question and the chips. The two bits are identical in either direction —
 * about the plant, or about you — but "Why now?" reads as an accusation once a plant is overdue, and
 * "just my timing" claims a deliberate choice that forgetting never involves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WateringReasonBottomSheet(
    plantName: String,
    onDismiss: () -> Unit,
    onLog: (WateringReason?) -> Unit,
    gapRanLong: Boolean = false,
    title: String = stringResource(R.string.water_feedback_sheet_title, plantName)
) {
    var selected by remember { mutableStateOf<WateringReason?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (gapRanLong) R.string.water_reason_question_late else R.string.water_reason_question
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            for (reason in WateringReason.entries) {
                FilterChip(
                    selected = selected == reason,
                    onClick = { selected = if (selected == reason) null else reason },
                    label = { Text(stringResource(reason.labelRes(gapRanLong))) }
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onLog(selected) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.quick_water_log))
            }
        }
    }
}

/**
 * The reschedule reason prompt (#586, product ADR-0030) — the symmetric half of
 * [WateringReasonBottomSheet]. Unlike the watering prompt there is no "log anyway" button: choosing
 * an option *is* the answer and advances straight to the date picker, and dismissing the sheet
 * abandons the reschedule entirely, recording no signal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescheduleReasonBottomSheet(
    onDismiss: () -> Unit,
    onReasonChosen: (RescheduleReason) -> Unit
) {
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
                text = stringResource(R.string.reschedule_watering_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.reschedule_reason_question),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            for (reason in RescheduleReason.entries) {
                OutlinedButton(
                    onClick = { onReasonChosen(reason) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(reason.labelRes()))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
