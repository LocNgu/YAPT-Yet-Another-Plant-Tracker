package com.yapt.planttracker.ui.screens.away

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.away.AwayTripStatus
import com.yapt.planttracker.domain.away.canConfirmOn
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitState
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Settings' always-visible Away mode section (product ADR-0065); [onMessage] shows a snackbar on the host. */
@Composable
fun AwayModeSettingsSection(
    viewModel: AwayModeViewModel,
    onMessage: (String) -> Unit,
    navigation: AwayModeNavigation = AwayModeNavigation(),
    confirmVisitViewModel: ConfirmVisitViewModel? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AwayModeEventsEffect(viewModel, onMessage)
    confirmVisitViewModel?.let { ConfirmVisitSheetHost(it, onMessage) }
    val scope = rememberCoroutineScope()
    state?.let { current ->
        AwayModeSettingsContent(
            state = current,
            actions = AwayModeActions(
                onSave = { first, last, editingId ->
                    val saved = viewModel.saveTrip(first, last, editingId)
                    // The rhythm step follows a new trip, once it is stored, so the screen never opens on "no trip".
                    if (editingId == null) {
                        scope.launch {
                            saved.join()
                            navigation.onSitterRhythm()
                        }
                    }
                },
                onCancelTrip = { viewModel.cancelTrip() },
                onImBack = { viewModel.imBack() }
            ),
            navigation = navigation,
            onConfirmVisit = { id -> confirmVisitViewModel?.open(id) }
        )
    }
}

@Composable
internal fun AwayModeSettingsContent(
    state: AwayModeUiState,
    actions: AwayModeActions,
    navigation: AwayModeNavigation = AwayModeNavigation(),
    onConfirmVisit: (visitId: Long) -> Unit = {}
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val trip = when (val status = state.status) {
        AwayTripStatus.None -> null
        is AwayTripStatus.Upcoming -> status.trip
        is AwayTripStatus.Away -> status.trip
    }

    Text(
        text = stringResource(R.string.away_section_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
    Text(
        text = stringResource(R.string.away_section_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    AwayModeStatusRow(state)
    AwayModeTripButtons(state, actions, onEditDates = { showSheet = true }, onPlan = { showSheet = true })
    AwayModeSitterButtons(state, navigation)
    AwayModeVisitList(state, onConfirmVisit)
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    if (showSheet) {
        AwayModeDateRangeSheet(
            state = state,
            editingId = trip?.id,
            initialRange = trip?.let { it.firstDay to it.lastDay },
            onDismiss = { showSheet = false },
            onSave = { first, last -> actions.onSave(first, last, trip?.id) }
        )
    }
}

@Composable
private fun AwayModeStatusRow(state: AwayModeUiState) {
    val subtitle = awayStatusText(state)
    val stateText = stringResource(
        when (state.status) {
            AwayTripStatus.None -> R.string.away_state_none
            is AwayTripStatus.Upcoming -> R.string.away_state_planned
            is AwayTripStatus.Away -> R.string.away_state_away
        }
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { stateDescription = stateText },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.FlightTakeoff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.away_row_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            awayRhythmText(state)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun awayStatusText(state: AwayModeUiState): String = when (val status = state.status) {
    AwayTripStatus.None -> stringResource(R.string.away_status_none)
    is AwayTripStatus.Upcoming -> stringResource(
        R.string.away_status_planned,
        awayDayText(status.trip.firstDay, state.today),
        awayDayText(status.trip.lastDay, state.today),
        awayDayText(status.trip.lastDay.plusDays(1), state.today)
    )
    is AwayTripStatus.Away -> stringResource(
        R.string.away_status_away,
        awayDayText(status.trip.firstDay, state.today),
        awayDayText(status.trip.lastDay, state.today),
        awayDayText(status.trip.lastDay.plusDays(1), state.today)
    )
}

@Composable
private fun awayRhythmText(state: AwayModeUiState): String? {
    val trip = when (val status = state.status) {
        AwayTripStatus.None -> null
        is AwayTripStatus.Upcoming -> status.trip
        is AwayTripStatus.Away -> status.trip
    }
    val days = trip?.visitEveryDays
    return when {
        trip == null -> null
        days == null -> stringResource(R.string.away_rhythm_not_set)
        else -> pluralStringResource(R.plurals.away_rhythm_set, days, days)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AwayModeTripButtons(
    state: AwayModeUiState,
    actions: AwayModeActions,
    onEditDates: () -> Unit,
    onPlan: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (state.status) {
            AwayTripStatus.None ->
                Button(onClick = onPlan) { Text(stringResource(R.string.away_action_plan)) }
            is AwayTripStatus.Upcoming -> {
                OutlinedButton(onClick = onEditDates) { Text(stringResource(R.string.away_action_edit)) }
                TextButton(onClick = actions.onCancelTrip) { Text(stringResource(R.string.away_action_cancel)) }
            }
            is AwayTripStatus.Away -> {
                OutlinedButton(onClick = onEditDates) { Text(stringResource(R.string.away_action_edit)) }
                Button(onClick = actions.onImBack) { Text(stringResource(R.string.away_action_back)) }
            }
        }
    }
}

/** The slice-4 entry points (product ADR-0065): the sitter rhythm, planning a visit, and Before you leave. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AwayModeSitterButtons(state: AwayModeUiState, navigation: AwayModeNavigation) {
    if (state.status == AwayTripStatus.None) return
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = navigation.onSitterRhythm) {
            Text(stringResource(R.string.away_action_sitter_rhythm))
        }
        OutlinedButton(onClick = navigation.onPlanVisit) {
            Text(stringResource(R.string.away_action_plan_visit))
        }
        if (state.canOpenBeforeYouLeave) {
            OutlinedButton(onClick = navigation.onBeforeYouLeave) {
                Text(stringResource(R.string.away_action_before_leave))
            }
        }
    }
}

/** The current or most recent trip's sitter visits with their state; a planned one can be confirmed from here. */
@Composable
private fun AwayModeVisitList(state: AwayModeUiState, onConfirmVisit: (visitId: Long) -> Unit) {
    val visits = state.settingsVisits
    if (visits.isEmpty()) return
    Text(
        text = stringResource(R.string.sitter_visits_heading),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
    visits.forEach { visit -> AwayModeVisitRow(visit, state.today, onConfirmVisit) }
}

@Composable
private fun AwayModeVisitRow(visit: SitterVisit, today: LocalDate, onConfirmVisit: (visitId: Long) -> Unit) {
    val stateText = stringResource(
        when (visit.state) {
            SitterVisitState.PLANNED -> R.string.sitter_visit_state_planned
            SitterVisitState.CONFIRMED -> R.string.sitter_visit_state_confirmed
            SitterVisitState.DID_NOT_HAPPEN -> R.string.sitter_visit_state_did_not_happen
        }
    )
    val total = visit.plants.size
    val detail = if (visit.state == SitterVisitState.CONFIRMED) {
        val watered = visit.plants.count { it.watered == true }
        pluralStringResource(R.plurals.sitter_visit_watered_count, total, watered, total)
    } else {
        pluralStringResource(R.plurals.sitter_visit_plant_count, total, total)
    }
    val dayText = awayWeekdayDayText(visit.day, today)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { stateDescription = stateText }
        ) {
            Text(dayText, style = MaterialTheme.typography.bodyLarge)
            Text(
                "$stateText · $detail",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (visit.canConfirmOn(today)) {
            val description = stringResource(R.string.sitter_visit_confirm_description, dayText)
            TextButton(
                onClick = { onConfirmVisit(visit.id) },
                modifier = Modifier.semantics { contentDescription = description }
            ) {
                Text(stringResource(R.string.sitter_visit_confirm_action))
            }
        }
    }
}
