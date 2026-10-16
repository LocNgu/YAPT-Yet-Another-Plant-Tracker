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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.away.AwayTripStatus

/** Settings' always-visible Away mode section (product ADR-0065); [onMessage] shows a snackbar on the host. */
@Composable
fun AwayModeSettingsSection(viewModel: AwayModeViewModel, onMessage: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AwayModeEventsEffect(viewModel, onMessage)
    state?.let { current ->
        AwayModeSettingsContent(
            state = current,
            actions = AwayModeActions(
                onSave = { first, last, editingId -> viewModel.saveTrip(first, last, editingId) },
                onCancelTrip = { viewModel.cancelTrip() },
                onImBack = { viewModel.imBack() }
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AwayModeSettingsContent(state: AwayModeUiState, actions: AwayModeActions) {
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
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (state.status) {
            AwayTripStatus.None ->
                Button(onClick = { showSheet = true }) { Text(stringResource(R.string.away_action_plan)) }
            is AwayTripStatus.Upcoming -> {
                OutlinedButton(onClick = { showSheet = true }) { Text(stringResource(R.string.away_action_edit)) }
                TextButton(onClick = actions.onCancelTrip) { Text(stringResource(R.string.away_action_cancel)) }
            }
            is AwayTripStatus.Away -> {
                OutlinedButton(onClick = { showSheet = true }) { Text(stringResource(R.string.away_action_edit)) }
                Button(onClick = actions.onImBack) { Text(stringResource(R.string.away_action_back)) }
            }
        }
    }
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
