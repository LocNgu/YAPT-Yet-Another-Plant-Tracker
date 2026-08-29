package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Disambiguates the watering-due row's "Water" button from the `plant_detail_tab_water` tab strip
 * label (same string, both clickable) in Compose UI tests (#508 review fix) — text alone can't tell
 * them apart via `onNodeWithText`.
 */
internal const val WATERING_DUE_WATER_BUTTON_TEST_TAG = "watering_due_water_button"

/**
 * Disambiguates [FertilizeDueActionRow]'s "Fertilize" button from the `plant_detail_tab_fertilize`
 * tab strip label (same string, both clickable) in Compose UI tests — same rationale as
 * [WATERING_DUE_WATER_BUTTON_TEST_TAG] (#508 review fix).
 */
internal const val FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG = "fertilize_due_action_button"

/**
 * The two watering-due actions row (#586, product ADR-0030, narrowing #508/ADR-0029's three):
 * **Water** and **Reschedule watering**, in both the classic layout and the Water tab — see
 * `.claude/rules/plant-detail.md`. "Did water go in, or not?" is a fact, not a judgement, so the user
 * never has to work out *why* they are deferring in order to pick a button; the reason is asked
 * afterwards, and only when the action is off-schedule.
 *
 * "Still moist" is gone as a top-level action, not as a behaviour: it is now the "Soil still moist"
 * answer to the Reschedule prompt, writing the same `CareType.CHECK` log through the same
 * `QuickLogUseCase.recordStillMoistCheck()` call site.
 *
 * Water is a filled primary [Button] (water-drop icon + text, `colorScheme.primary` — resolving to
 * `SageGreen`/`SageGreenLight` in both themes, no hardcoded color); Reschedule is a secondary,
 * icon-only [OutlinedIconButton] (`Icons.Filled.MoreTime`, no visible text — its `contentDescription`
 * reuses [R.string.reschedule_watering_title]) so Water's [Modifier.weight] naturally takes the rest
 * of the row's width (#603 round-3 visual polish).
 */
@Composable
internal fun WateringDueActionsRow(
    onWaterClick: () -> Unit,
    onRescheduleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onWaterClick,
            modifier = Modifier.weight(1f).testTag(WATERING_DUE_WATER_BUTTON_TEST_TAG)
        ) {
            Icon(Icons.Filled.WaterDrop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.watering_due_action_water))
        }
        OutlinedIconButton(onClick = onRescheduleClick) {
            Icon(
                Icons.Filled.MoreTime,
                contentDescription = stringResource(R.string.reschedule_watering_title)
            )
        }
    }
}

/**
 * Fertilize tab's single always-visible action button (#603), replacing the fertilizing `StatChip`'s
 * `onFertilizeClick` entry point once `StatsRow` is dropped from the tabs layout. No "reschedule"
 * counterpart — fertilizing has no equivalent concept, so this is one button, not a row of two.
 */
@Composable
internal fun FertilizeDueActionRow(
    onFertilizeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        OutlinedButton(
            onClick = onFertilizeClick,
            modifier = Modifier.weight(1f).testTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG)
        ) {
            Text(stringResource(R.string.bulk_action_fertilize))
        }
    }
}

/**
 * The "Reschedule watering" dialog (#508, product ADR-0029, replaces the 1-7 day stepper): Today /
 * +1 / +2 / +3 days / a Material 3 [DatePicker] for a custom date. [todayEnabled] is `false` while
 * the plant's effective due date is already today (a true no-op there) and `true` while overdue.
 * Every option writes `wateringDueDateOverride` only via [onToday]/[onRelativeDays]/[onCustomDate] —
 * this dialog never fires the ADR-0006 interval-suggestion dialog, unlike the flow it replaces.
 *
 * Reached only after the reason prompt since #586 (product ADR-0030). [suggestedDays], non-null only
 * for a "Soil still moist" reschedule, adds one recommended option at the top derived from the
 * interval the model lands on *after* that observation — the replacement for #570's flat
 * `STILL_MOIST_DEFERRAL_DAYS = 1`, which could not clear "due" for a plant overdue by two or more
 * days. **How many days the user then picks is never an input to the model** (#586): the reason
 * already decided what is learned.
 */
@Composable
internal fun RescheduleWateringDialog(
    todayEnabled: Boolean,
    onDismiss: () -> Unit,
    onToday: () -> Unit,
    onRelativeDays: (Int) -> Unit,
    onCustomDate: (Long) -> Unit,
    suggestedDays: Int? = null
) {
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        RescheduleDatePickerDialog(
            onDismiss = onDismiss,
            onConfirm = { utcMidnightMs ->
                showDatePicker = false
                utcMidnightMs?.let { onCustomDate(utcMidnightMsToLocalStartOfDayMillis(it)) }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reschedule_watering_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (suggestedDays != null) {
                    RescheduleOption(
                        label = pluralStringResource(
                            R.plurals.reschedule_watering_suggested_days,
                            suggestedDays,
                            suggestedDays
                        ),
                        onClick = { onRelativeDays(suggestedDays) }
                    )
                }
                RescheduleOption(
                    label = stringResource(R.string.reschedule_watering_today),
                    onClick = onToday,
                    enabled = todayEnabled
                )
                for (days in RELATIVE_DAY_OPTIONS) {
                    RescheduleOption(
                        label = pluralStringResource(R.plurals.reschedule_watering_plus_days, days, days),
                        onClick = { onRelativeDays(days) }
                    )
                }
                RescheduleOption(
                    label = stringResource(R.string.reschedule_watering_custom_date),
                    onClick = { showDatePicker = true }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** The fixed relative-deferral options offered between "Today" and "Custom date…". */
private val RELATIVE_DAY_OPTIONS = listOf(1, 2, 3)

/** One full-width row of [RescheduleWateringDialog]'s option list. */
@Composable
private fun RescheduleOption(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

/**
 * The "Custom date…" branch of [RescheduleWateringDialog], extracted so the option list stays
 * readable. [TodayOrLaterSelectableDates] already excludes past dates, so the picked value needs no
 * further validation here. [onConfirm] receives the raw `selectedDateMillis`, which is `null` until
 * the user actually taps a day — OK closes the picker either way, and the caller does the
 * UTC-midnight reinterpretation documented on [utcMidnightMsToLocalStartOfDayMillis].
 */
@Composable
private fun RescheduleDatePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (utcMidnightMs: Long?) -> Unit
) {
    val datePickerState = rememberDatePickerState(selectableDates = TodayOrLaterSelectableDates)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(datePickerState.selectedDateMillis) }
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

/**
 * [DatePicker]'s `selectedDateMillis`/`SelectableDates` always operate on UTC midnight, regardless of
 * device timezone (a documented Material3 API quirk, and also how Material3's own `CalendarModel`
 * highlights "today"). The calendar day number is read out of [utcTimeMillis] in [ZoneOffset.UTC] since
 * that's the zone the picker encodes it in — but it's compared against **local** "today"
 * ([zoneId], defaulting to [ZoneId.systemDefault]), not UTC "today": [utcMidnightMsToLocalStartOfDayMillis]
 * always reinterprets the picked day as a local calendar day downstream, matching how
 * `CareSchedule.dueStatusFor`'s `isOverdue`/`isDueSoon` compare via `Long.toLocalDate()` (also
 * [ZoneId.systemDefault]). Comparing against UTC "today" instead would let a user in a timezone ahead
 * of UTC (e.g. UTC+9) tap the picker's own highlighted "today" cell during local hours before the UTC
 * day rolls over and end up with a `wateringDueDateOverride` whose local calendar day is still in the
 * past relative to their actual today.
 */
internal fun isOnOrAfterLocalToday(
    utcTimeMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zoneId),
): Boolean {
    val candidate = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return !candidate.isBefore(today)
}

private object TodayOrLaterSelectableDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = isOnOrAfterLocalToday(utcTimeMillis)
}

/** Converts a picked UTC-midnight date to local start-of-day, mirroring `AddCareLogScreen`'s date-picker handling. */
private fun utcMidnightMsToLocalStartOfDayMillis(utcMidnightMs: Long): Long {
    val pickedDate = Instant.ofEpochMilli(utcMidnightMs).atZone(ZoneOffset.UTC).toLocalDate()
    return pickedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
