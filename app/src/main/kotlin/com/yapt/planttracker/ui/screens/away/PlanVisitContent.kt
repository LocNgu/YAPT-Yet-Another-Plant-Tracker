package com.yapt.planttracker.ui.screens.away

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.away.RoomCount
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.ui.components.SteppedSlider
import com.yapt.planttracker.ui.components.SteppedSliderCallbacks
import com.yapt.planttracker.ui.components.SteppedSliderLabels
import com.yapt.planttracker.ui.components.yaptFilterChipColors
import java.time.LocalDate

/** A day the picker may select: inside the trip and not in the past ([utcMillis] is UTC-midnight encoded). */
internal fun isSelectableVisitDay(utcMillis: Long, earliest: LocalDate, latest: LocalDate): Boolean =
    utcMillis.datePickerMillisToLocalDate() in earliest..latest

@Composable
internal fun PlanVisitContent(
    state: PlanVisitUiState.Plan,
    actions: PlanVisitActions,
    contentPadding: PaddingValues = PaddingValues()
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        WhenSection(state, actions.onPickDay)
        GapSection(state, actions.onSetGap)
        SummaryCard(state)
        val canShare = state.projection.listed.isNotEmpty() && !state.sharing
        Button(onClick = actions.onSharePdf, enabled = canShare, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sitter_plan_share_pdf))
        }
        OutlinedButton(onClick = actions.onShareText, enabled = canShare, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sitter_plan_share_text))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhenSection(state: PlanVisitUiState.Plan, onPickDay: (LocalDate) -> Unit) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val tomorrow = state.today.plusDays(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sitter_plan_when), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.visitDay == state.today,
                onClick = { onPickDay(state.today) },
                enabled = state.todayAllowed,
                label = { Text(stringResource(R.string.sitter_plan_today)) },
                colors = yaptFilterChipColors()
            )
            FilterChip(
                selected = state.visitDay == tomorrow,
                onClick = { onPickDay(tomorrow) },
                enabled = state.tomorrowAllowed,
                label = { Text(stringResource(R.string.sitter_plan_tomorrow)) },
                colors = yaptFilterChipColors()
            )
            val picked = state.visitDay != state.today && state.visitDay != tomorrow
            FilterChip(
                selected = picked,
                onClick = { showPicker = true },
                label = {
                    Text(
                        if (picked) {
                            awayWeekdayDayText(state.visitDay, state.today)
                        } else {
                            stringResource(R.string.sitter_plan_pick_date)
                        }
                    )
                },
                colors = yaptFilterChipColors()
            )
        }
    }
    if (showPicker) {
        VisitDatePickerDialog(state, onPickDay, onDismiss = { showPicker = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisitDatePickerDialog(
    state: PlanVisitUiState.Plan,
    onPickDay: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = state.visitDay.toDatePickerMillis(),
        selectableDates = remember(state.earliestDay, state.latestDay) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    isSelectableVisitDay(utcTimeMillis, state.earliestDay, state.latestDay)
            }
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { onPickDay(it.datePickerMillisToLocalDate()) }
                    onDismiss()
                },
                enabled = pickerState.selectedDateMillis != null
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    ) { DatePicker(state = pickerState) }
}

@Composable
private fun GapSection(state: PlanVisitUiState.Plan, onSetGap: (Int) -> Unit) {
    val gapText = pluralStringResource(R.plurals.sitter_plan_gap_value, state.gapDays, state.gapDays)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.sitter_plan_gap_title), style = MaterialTheme.typography.titleMedium)
        Text(gapText, style = MaterialTheme.typography.bodyLarge)
        SteppedSlider(
            value = state.gapDays,
            range = PlanVisitViewModel.MIN_GAP_DAYS..PlanVisitViewModel.MAX_GAP_DAYS,
            callbacks = SteppedSliderCallbacks(onValueChange = onSetGap),
            labels = SteppedSliderLabels(
                decreaseContentDescription = stringResource(R.string.sitter_plan_gap_decrease),
                increaseContentDescription = stringResource(R.string.sitter_plan_gap_increase),
                stateDescription = gapText
            )
        )
        Text(
            stringResource(
                R.string.sitter_plan_includes_due_by,
                awayDayText(state.projection.includesDueBy, state.today)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SummaryCard(state: PlanVisitUiState.Plan) {
    val summary = state.summary
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (summary.plantCount == 0) {
                    stringResource(R.string.sitter_plan_nothing_to_water)
                } else {
                    pluralStringResource(R.plurals.sitter_water_count, summary.plantCount, summary.plantCount)
                },
                style = MaterialTheme.typography.titleMedium
            )
            if (summary.roomCounts.isNotEmpty()) Text(roomCountsText(summary.roomCounts))
            if (summary.restingCount > 0) {
                Text(
                    pluralStringResource(R.plurals.sitter_plan_resting, summary.restingCount, summary.restingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (summary.missedLastTime.isNotEmpty()) {
                Note(R.plurals.sitter_plan_note_missed, summary.missedLastTime.map { it.name })
            }
            if (summary.longerThanUsual.isNotEmpty()) {
                Note(
                    R.plurals.sitter_plan_note_longer,
                    summary.longerThanUsual.map { it.plant.name },
                    pluralStringResource(R.plurals.sitter_plan_note_longer_hint, state.gapDays, state.gapDays)
                )
            }
            if (summary.noPhoto.isNotEmpty()) Note(R.plurals.sitter_plan_note_no_photo, summary.noPhoto.names())
            if (summary.lookAlikes.isNotEmpty()) {
                Note(R.plurals.sitter_plan_note_look_alike, summary.lookAlikes.names())
            }
        }
    }
}

private fun List<Plant>.names(): List<String> = map { it.name }

@Composable
private fun roomCountsText(counts: List<RoomCount>): String {
    val noRoom = stringResource(R.string.sitter_room_none)
    return counts
        .map { stringResource(R.string.sitter_plan_room_count, it.room ?: noRoom, it.count) }
        .joinToString(stringResource(R.string.sitter_plan_rooms_separator))
}

@Composable
private fun namesText(names: List<String>): String {
    val (shown, more) = cappedNames(names)
    return if (more > 0) stringResource(R.string.sitter_plan_names_more, shown, more) else shown
}

@Composable
private fun Note(plurals: Int, names: List<String>, hint: String? = null) {
    val text = pluralStringResource(plurals, names.size, names.size, namesText(names))
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 2.dp)
        )
        Column {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
