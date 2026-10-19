package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone

/**
 * Plant Detail's shared "pick a date, then confirm" bottom-sheet content (#654, #675, #694) — split
 * out of `WateringDueActions.kt` to stay under Detekt's per-file `TooManyFunctions` threshold, the same
 * reasoning `CustomRemindersSection.kt`/`PlantIssuesSection.kt` already use (see
 * `.claude/rules/plant-detail.md`).
 */

/** Locates [LogWateringDatePickerDialog]'s `ModalBottomSheet` in Compose UI tests. */
internal const val LOG_WATERING_DATE_PICKER_TEST_TAG = "log_watering_date_picker_dialog"

/**
 * [WateringDueActions.kt]'s `isOnOrAfterLocalToday`'s inverse, for [CareDatePickerContent] (#654) — a
 * backdated log is not-future-only (today and earlier), the opposite direction from
 * `RescheduleWateringDialog`'s custom date, which excludes the past.
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
 * The inverse conversion (#694): re-encodes an already-picked `loggedAt` instant's *local* calendar
 * day as UTC midnight, so a picker can be re-opened pre-selected on a previously chosen date (the
 * Add-photo sheet's date-edit state, [PlantDetailPhotoCapture.kt]) rather than always defaulting back
 * to today. Mirrors [localTodayAsUtcMidnightMillis]'s encoding direction, just fed an arbitrary
 * instant instead of "today".
 */
internal fun localDayToUtcMidnightMillis(
    loggedAt: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long = Instant.ofEpochMilli(loggedAt).atZone(zoneId).toLocalDate()
    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * The reusable "pick a date" content (#694) behind both [CareDatePickerBottomSheet] (Repot's own
 * sheet) and the Add-photo sheet's date-edit state — content, not a sheet of its own, so a host that
 * already owns a `ModalBottomSheet` (Add-photo) can swap this in without nesting two sheets.
 *
 * Presented in a `ModalBottomSheet` (#675) rather than a centered `DatePickerDialog`, matching the
 * bottom-sheet convention the rest of Plant Detail's action prompts use (`WateringReasonBottomSheet`,
 * `WateringExplanationSheet`) — see `.claude/rules/plant-detail.md`'s
 * `Follow-up (#675)` note. Uses `DatePicker`'s default `title` (no override): the earlier `DatePickerDialog`
 * container had a custom-`title`-slot clipping bug (#654 UI feedback — a custom `title` slot replaces
 * Material3's own title composable entirely, losing the padding that composable applies internally, so a
 * bare `Text` sat flush against the dialog's rounded top corner), which no longer applies now that
 * `DatePicker` sits directly inside a sheet `Column` rather than a `DatePickerDialog`, but the default-title
 * choice itself is unchanged and still matches `AddCareLogScreen`'s own picker.
 *
 * The `DatePicker` sits in its own inner `Column` (`Modifier.weight(1f, fill = false).verticalScroll(...)`),
 * not the caller's outer `Column` directly (external review, PR #696; must be called from a
 * [ColumnScope] for `weight` to resolve): `skipPartiallyExpanded = true` alone only removes the
 * sheet's partial-expansion anchor, it does not shrink oversized content to fit, so a viewport shorter
 * than the full ~568dp `DatePicker` (landscape, a resized multi-window) would otherwise clip the
 * Cancel/OK row below the visible sheet with no way to confirm or cancel (product ADR-0037).
 * `weight(1f, fill = false)` caps the inner `Column` at whatever height remains after the
 * always-visible button row is measured — letting the calendar scroll internally when it doesn't fit —
 * without forcing the sheet to full height when the calendar comfortably fits on a normal portrait
 * phone.
 *
 * [onConfirm] receives a [Long] timestamp with the *picked* calendar date but the *current* wall-clock
 * time-of-day via a `Calendar` field-copy —
 * only the date changes, never the time. Not called at all if the user confirms with no date selected
 * (shouldn't happen once a day is pre-selected, but mirrors `RescheduleDatePickerDialog`'s existing
 * null-safety for the same picker API).
 */
@Composable
internal fun ColumnScope.CareDatePickerContent(
    initialSelectedDateMillis: Long,
    onCancel: () -> Unit,
    onConfirm: (loggedAt: Long) -> Unit
) {
    CareDatePickerBody(
        initialSelectedDateMillis = initialSelectedDateMillis,
        onCancel = onCancel,
        soilTooDryVisibleFor = null,
        onConfirm = { loggedAt, _ -> onConfirm(loggedAt) }
    )
}

/**
 * [CareDatePickerContent]'s implementation, plus the watering-only "Soil was already too dry" option
 * (#838, product ADR-0067). [soilTooDryVisibleFor] is `null` for every non-watering picker (Repot,
 * Prune, Add photo), which then never shows the option. Otherwise it is asked, live and off the main
 * path, whether the option applies to the currently picked date — it depends on that date's own
 * predecessor, which is a DB read — and the checkbox appears only while the answer is yes. The tick is
 * reported to [onConfirm] only if the option is visible for the date confirmed, so ticking and then
 * moving to a late date can never apply a stale answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.CareDatePickerBody(
    initialSelectedDateMillis: Long,
    onCancel: () -> Unit,
    soilTooDryVisibleFor: (suspend (loggedAt: Long) -> Boolean)?,
    onConfirm: (loggedAt: Long, soilTooDry: Boolean) -> Unit
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialSelectedDateMillis,
        selectableDates = TodayOrEarlierSelectableDates
    )
    var soilTooDryTicked by rememberSaveable { mutableStateOf(false) }
    val currentVisibleFor = rememberUpdatedState(soilTooDryVisibleFor)
    val selectedDateMillis = datePickerState.selectedDateMillis
    val soilTooDryVisible by produceState(initialValue = false, selectedDateMillis) {
        value = false
        val visibleFor = currentVisibleFor.value
        if (visibleFor != null && selectedDateMillis != null) {
            value = visibleFor(utcMidnightMsToLoggedAtMillis(selectedDateMillis))
        }
    }
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
    ) {
        DatePicker(state = datePickerState)
    }
    if (soilTooDryVisible) {
        SoilTooDryOption(
            checked = soilTooDryTicked,
            onCheckedChange = { soilTooDryTicked = it }
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = {
                datePickerState.selectedDateMillis?.let { utcMidnightMs ->
                    onConfirm(utcMidnightMsToLoggedAtMillis(utcMidnightMs), soilTooDryTicked && soilTooDryVisible)
                }
                onCancel()
            }
        ) { Text(stringResource(R.string.ok)) }
    }
}

@Composable
private fun SoilTooDryOption(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.soil_too_dry_option))
    }
}

/**
 * A [ModalBottomSheet] wrapping [CareDatePickerContent] (#694) — Repot's own date picker, and the
 * general-purpose shape any future single-purpose "pick a date" sheet on this screen should reuse.
 * `skipPartiallyExpanded = true` is load-bearing (product ADR-0037) — see [CareDatePickerContent]'s doc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CareDatePickerBottomSheet(
    testTag: String,
    onDismiss: () -> Unit,
    onConfirm: (loggedAt: Long) -> Unit,
    initialSelectedDateMillis: Long = localTodayAsUtcMidnightMillis()
) {
    CareDatePickerSheetHost(testTag, onDismiss) {
        CareDatePickerContent(
            initialSelectedDateMillis = initialSelectedDateMillis,
            onCancel = onDismiss,
            onConfirm = onConfirm
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CareDatePickerSheetHost(
    testTag: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(testTag)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * The quick-water/quick-liquid-fertilize entry points' "Log watering" date picker (#654, superseding
 * the earlier long-press-only spec — see the issue's spec-clarification amendment): a plain tap on
 * Water/the combined Water+Fertilize action now **always** opens this first, pre-selected to today, in
 * place of the old instant "log now" fast path. Confirming with today selected reproduces that old fast
 * path in one extra confirm tap; picking an earlier date backdates the log.
 *
 * Also hosts the optional "Soil was already too dry" tick (#838, product ADR-0067), driven by
 * [soilTooDryVisibleFor] (see [CareDatePickerBody]); [onConfirm] gets the tick as its second argument.
 * Plant Detail and the Care tiles both open it. [LOG_WATERING_DATE_PICKER_TEST_TAG]'s string value is
 * deliberately unchanged so every existing instrumented test keeps working.
 */
@Composable
internal fun LogWateringDatePickerDialog(
    onDismiss: () -> Unit,
    soilTooDryVisibleFor: (suspend (loggedAt: Long) -> Boolean)? = null,
    onConfirm: (loggedAt: Long, soilTooDry: Boolean) -> Unit
) {
    CareDatePickerSheetHost(LOG_WATERING_DATE_PICKER_TEST_TAG, onDismiss) {
        CareDatePickerBody(
            initialSelectedDateMillis = localTodayAsUtcMidnightMillis(),
            onCancel = onDismiss,
            soilTooDryVisibleFor = soilTooDryVisibleFor,
            onConfirm = onConfirm
        )
    }
}

/**
 * [utcMidnightMs] (the picker's UTC-midnight-encoded selected day) reinterpreted as a local calendar
 * day, with the current wall-clock time-of-day copied on via a `Calendar` field-copy,
 * so a backdated quick-water's `CareLog.loggedAt`
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
