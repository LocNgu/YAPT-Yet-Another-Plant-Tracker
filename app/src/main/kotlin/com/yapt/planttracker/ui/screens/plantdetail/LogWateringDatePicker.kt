package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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

/** Locates [LogWateringDatePickerDialog]'s `ModalBottomSheet` in Compose UI tests. */
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
 * The picker's `initialSelectedDateMillis` (#654 round-2 review fix): Material3's `DatePicker`
 * interprets that parameter as a UTC-midnight-encoded calendar date, not a raw instant — passing
 * [System.currentTimeMillis] directly (a UTC instant) silently preselects the wrong calendar day
 * whenever the device's local calendar day differs from UTC's (e.g. early morning in UTC+14, or
 * late evening in UTC−8). Mirrors [isOnOrBeforeLocalToday]'s own local-day → UTC-midnight
 * reinterpretation, just in the opposite direction (encoding a local day, not decoding one).
 */
internal fun localTodayAsUtcMidnightMillis(
    zoneId: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zoneId),
): Long = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

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
 *
 * Presented in a `ModalBottomSheet` (#675) rather than a centered `DatePickerDialog`, matching the
 * bottom-sheet convention the rest of Plant Detail's action prompts use (`WateringReasonBottomSheet`,
 * `RescheduleReasonBottomSheet`, `WateringExplanationSheet`) — see `.claude/rules/plant-detail.md`'s
 * `Follow-up (#675)` note. Uses `DatePicker`'s default `title` (no override): the earlier `DatePickerDialog`
 * container had a custom-`title`-slot clipping bug (#654 UI feedback — a custom `title` slot replaces
 * Material3's own title composable entirely, losing the padding that composable applies internally, so a
 * bare `Text` sat flush against the dialog's rounded top corner), which no longer applies now that
 * `DatePicker` sits directly inside a sheet `Column` rather than a `DatePickerDialog`, but the default-title
 * choice itself is unchanged and still matches `AddCareLogScreen`'s own picker.
 *
 * The `DatePicker` sits in its own inner `Column` (`Modifier.weight(1f, fill = false).verticalScroll(...)`),
 * not the outer sheet `Column` directly (external review, PR #696): `skipPartiallyExpanded = true` alone
 * only removes the sheet's partial-expansion anchor, it does not shrink oversized content to fit, so a
 * viewport shorter than the full ~568dp `DatePicker` (landscape, a resized multi-window) would otherwise
 * clip the Cancel/OK row below the visible sheet with no way to confirm or cancel. `weight(1f, fill =
 * false)` caps the inner `Column` at whatever height remains after the always-visible button row is
 * measured — letting the calendar scroll internally when it doesn't fit — without forcing the sheet to
 * full height when the calendar comfortably fits on a normal portrait phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogWateringDatePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (loggedAt: Long) -> Unit
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = localTodayAsUtcMidnightMillis(),
        selectableDates = TodayOrEarlierSelectableDates
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(LOG_WATERING_DATE_PICKER_TEST_TAG)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                DatePicker(state = datePickerState)
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
                        datePickerState.selectedDateMillis?.let { utcMidnightMs ->
                            onConfirm(utcMidnightMsToLoggedAtMillis(utcMidnightMs))
                        }
                        onDismiss()
                    }
                ) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}

/**
 * [utcMidnightMs] (the picker's UTC-midnight-encoded selected day) reinterpreted as a local calendar
 * day, with the current wall-clock time-of-day copied on — the exact `Calendar` field-copy pattern
 * `AddCareLogScreen.kt`'s own new-log date picker uses, so a backdated quick-water's `CareLog.loggedAt`
 * carries a realistic time-of-day rather than local midnight. [wallClockNowMs] defaults to the real
 * wall-clock time but is overridable so a unit test can pin the time-of-day being copied on rather than
 * depending on whenever the test happens to run.
 */
internal fun utcMidnightMsToLoggedAtMillis(
    utcMidnightMs: Long,
    wallClockNowMs: Long = System.currentTimeMillis()
): Long {
    val pickerCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    pickerCal.timeInMillis = utcMidnightMs
    val localCal = Calendar.getInstance()
    localCal.timeInMillis = wallClockNowMs
    localCal.set(Calendar.YEAR, pickerCal.get(Calendar.YEAR))
    localCal.set(Calendar.MONTH, pickerCal.get(Calendar.MONTH))
    localCal.set(Calendar.DAY_OF_MONTH, pickerCal.get(Calendar.DAY_OF_MONTH))
    return localCal.timeInMillis
}
