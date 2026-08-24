package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The three watering-due actions row (#508, product ADR-0029), replacing the old single full-width
 * "Skip watering" button in both the classic layout and the Water tab — see
 * `.claude/rules/plant-detail.md`. [onWaterClick] opens the same [com.yapt.planttracker.ui.components
 * .WaterFeedbackBottomSheet] flow the tappable watering `StatChip` already uses; [onStillMoistClick]
 * and [onRescheduleClick] wire straight to [PlantDetailViewModel.recordStillMoist] /
 * [PlantDetailViewModel.requestReschedule].
 */
@Composable
internal fun WateringDueActionsRow(
    onWaterClick: () -> Unit,
    onStillMoistClick: () -> Unit,
    onRescheduleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onWaterClick, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.watering_due_action_water))
        }
        OutlinedButton(onClick = onStillMoistClick, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.watering_due_action_still_moist))
        }
        OutlinedButton(onClick = onRescheduleClick, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.reschedule_watering_title))
        }
    }
}

/**
 * The "Reschedule watering" dialog (#508, product ADR-0029, replaces the 1-7 day stepper): Today /
 * +1 / +2 / +3 days / a Material 3 [DatePicker] for a custom date. [todayEnabled] is `false` while
 * the plant's effective due date is already today (a true no-op there) and `true` while overdue.
 * Every option writes `wateringDueDateOverride` only via [onToday]/[onRelativeDays]/[onCustomDate] —
 * this dialog never fires the ADR-0006 interval-suggestion dialog, unlike the flow it replaces.
 */
@Composable
internal fun RescheduleWateringDialog(
    todayEnabled: Boolean,
    onDismiss: () -> Unit,
    onToday: () -> Unit,
    onRelativeDays: (Int) -> Unit,
    onCustomDate: (Long) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(selectableDates = TodayOrLaterSelectableDates)
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { utcMidnightMs ->
                            onCustomDate(utcMidnightMsToLocalStartOfDayMillis(utcMidnightMs))
                        }
                        showDatePicker = false
                    }
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reschedule_watering_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onToday,
                    enabled = todayEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.reschedule_watering_today)) }
                TextButton(
                    onClick = { onRelativeDays(1) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(pluralStringResource(R.plurals.reschedule_watering_plus_days, 1, 1)) }
                TextButton(
                    onClick = { onRelativeDays(2) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(pluralStringResource(R.plurals.reschedule_watering_plus_days, 2, 2)) }
                TextButton(
                    onClick = { onRelativeDays(3) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(pluralStringResource(R.plurals.reschedule_watering_plus_days, 3, 3)) }
                TextButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.reschedule_watering_custom_date)) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * [DatePicker]'s `selectedDateMillis`/`SelectableDates` always operate on UTC midnight, regardless of
 * device timezone (a documented Material3 API quirk) — comparisons here stay in [ZoneOffset.UTC] on
 * both sides so the calendar day the user taps is the calendar day excluded/accepted, matching what
 * the picker itself visually displays.
 */
private object TodayOrLaterSelectableDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val candidate = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return !candidate.isBefore(LocalDate.now(ZoneOffset.UTC))
    }
}

/** Converts a picked UTC-midnight date to local start-of-day, mirroring `AddCareLogScreen`'s date-picker handling. */
private fun utcMidnightMsToLocalStartOfDayMillis(utcMidnightMs: Long): Long {
    val pickedDate = Instant.ofEpochMilli(utcMidnightMs).atZone(ZoneOffset.UTC).toLocalDate()
    return pickedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
