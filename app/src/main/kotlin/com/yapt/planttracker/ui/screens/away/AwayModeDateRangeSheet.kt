package com.yapt.planttracker.ui.screens.away

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.away.AwayPeriodValidation
import com.yapt.planttracker.domain.away.AwayPeriods
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal const val AWAY_DATE_RANGE_SHEET_TEST_TAG = "away_mode_date_range_sheet"

/**
 * Material3's date pickers hand dates back as UTC-midnight-encoded millis, not as instants in the local
 * zone, so a plain `toLocalDate()` would land a day off west or east of UTC (same trap as `CareDatePicker.kt`).
 */
internal fun LocalDate.toDatePickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun Long.datePickerMillisToLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * Plan or edit a trip. [editingId] is the trip being edited (null when planning); [initialRange] preselects
 * the picker. The picker accepts any date so each invalid range can say why it is invalid (slice 1's
 * [AwayPeriods.validate]); Save stays disabled until the range is valid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AwayModeDateRangeSheet(
    state: AwayModeUiState,
    editingId: Long?,
    initialRange: Pair<LocalDate, LocalDate>?,
    onDismiss: () -> Unit,
    onSave: (firstDay: LocalDate, lastDay: LocalDate) -> Unit
) {
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialRange?.first?.toDatePickerMillis(),
        initialSelectedEndDateMillis = initialRange?.second?.toDatePickerMillis(),
        initialDisplayedMonthMillis = (initialRange?.first ?: state.today).toDatePickerMillis()
    )
    val firstDay = pickerState.selectedStartDateMillis?.datePickerMillisToLocalDate()
    val lastDay = pickerState.selectedEndDateMillis?.datePickerMillisToLocalDate() ?: firstDay
    val validation = if (firstDay != null && lastDay != null) {
        AwayPeriods.validate(firstDay, lastDay, state.today, state.periods, editingId)
    } else {
        null
    }
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(AWAY_DATE_RANGE_SHEET_TEST_TAG)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
            if (validation != null && validation != AwayPeriodValidation.Valid) {
                Text(
                    text = context.awayValidationText(validation, state.today),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (firstDay != null && lastDay != null) onSave(firstDay, lastDay)
                        onDismiss()
                    },
                    enabled = validation == AwayPeriodValidation.Valid
                ) { Text(stringResource(R.string.away_sheet_save)) }
            }
        }
    }
}
