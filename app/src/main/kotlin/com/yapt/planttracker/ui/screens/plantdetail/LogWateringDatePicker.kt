package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.yapt.planttracker.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone

/**
 * The quick-water/quick-liquid-fertilize "Log watering" date picker (#654) — split out of
 * `WateringDueActions.kt` to stay under Detekt's per-file `TooManyFunctions` threshold, the same
 * reasoning `CustomRemindersSection.kt`/`PlantIssuesSection.kt` already use (see
 * `.claude/rules/plant-detail.md`).
 */

/** Locates [LogWateringDatePickerDialog]'s `DatePickerDialog` in Compose UI tests. */
internal const val LOG_WATERING_DATE_PICKER_TEST_TAG = "log_watering_date_picker_dialog"

/**
 * [WateringDueActions.kt]'s `isOnOrAfterLocalToday`'s inverse, for [LogWateringDatePickerDialog]
 * (#654) — a backdated quick-water is not-future-only (today and earlier), the opposite direction
 * from `RescheduleWateringDialog`'s custom date, which excludes the past.
 */
internal fun isOnOrBeforeLocalToday(
    utcTimeMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zoneId),
): Boolean {
    val candidate = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return !candidate.isAfter(today)
}

private object TodayOrEarlierSelectableDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = isOnOrBeforeLocalToday(utcTimeMillis)
}

/**
 * The quick-water/quick-liquid-fertilize entry points' "Log watering" date picker (#654, superseding
 * the earlier long-press-only spec — see the issue's spec-clarification amendment): a plain tap on
 * Water/the combined Water+Fertilize action now **always** opens this first, pre-selected to today, in
 * place of the old instant "log now" fast path. Confirming with today selected reproduces that old fast
 * path in one extra confirm tap; picking an earlier date backdates the log. Not-future-only via
 * [TodayOrEarlierSelectableDates] — the opposite direction from `RescheduleWateringDialog`'s custom
 * date, which is about *deferring*, not backfilling.
 *
 * [onConfirm] receives a [Long] timestamp with the *picked* calendar date but the *current* wall-clock
 * time-of-day, mirroring `AddCareLogScreen`'s own new-log date-picker `Calendar` field-copy pattern —
 * only the date changes, never the time. Not called at all if the user confirms with no date selected
 * (shouldn't happen once a day is pre-selected, but mirrors `RescheduleDatePickerDialog`'s existing
 * null-safety for the same picker API).
 */
@Composable
internal fun LogWateringDatePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (loggedAt: Long) -> Unit
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis(),
        selectableDates = TodayOrEarlierSelectableDates
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { utcMidnightMs ->
                        onConfirm(utcMidnightMsToLoggedAtMillis(utcMidnightMs))
                    }
                    onDismiss()
                }
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        modifier = Modifier.testTag(LOG_WATERING_DATE_PICKER_TEST_TAG)
    ) {
        DatePicker(
            state = datePickerState,
            title = { Text(stringResource(R.string.log_watering_date_picker_title)) }
        )
    }
}

/**
 * [utcMidnightMs] (the picker's UTC-midnight-encoded selected day) reinterpreted as a local calendar
 * day, with the current wall-clock time-of-day copied on — the exact `Calendar` field-copy pattern
 * `AddCareLogScreen.kt`'s own new-log date picker uses, so a backdated quick-water's `CareLog.loggedAt`
 * carries a realistic time-of-day rather than local midnight.
 */
private fun utcMidnightMsToLoggedAtMillis(utcMidnightMs: Long): Long {
    val pickerCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    pickerCal.timeInMillis = utcMidnightMs
    val localCal = Calendar.getInstance()
    localCal.timeInMillis = System.currentTimeMillis()
    localCal.set(Calendar.YEAR, pickerCal.get(Calendar.YEAR))
    localCal.set(Calendar.MONTH, pickerCal.get(Calendar.MONTH))
    localCal.set(Calendar.DAY_OF_MONTH, pickerCal.get(Calendar.DAY_OF_MONTH))
    return localCal.timeInMillis
}
