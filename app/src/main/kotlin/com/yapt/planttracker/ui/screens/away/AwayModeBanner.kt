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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.away.AwayTripStatus

/** The Care banner (product ADR-0065): shown while away and for a week before a trip starts. */
@Composable
fun AwayModeBanner(
    viewModel: AwayModeViewModel,
    onMessage: (String) -> Unit,
    navigation: AwayModeNavigation = AwayModeNavigation()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AwayModeEventsEffect(viewModel, onMessage)
    state?.let {
        AwayModeBannerContent(
            it,
            onImBack = { viewModel.imBack() },
            onPlanVisit = navigation.onPlanVisit,
            onBeforeYouLeave = navigation.onBeforeYouLeave
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AwayModeBannerContent(
    state: AwayModeUiState,
    onImBack: () -> Unit,
    onPlanVisit: () -> Unit = {},
    onBeforeYouLeave: () -> Unit = {}
) {
    if (!state.showsBanner) return
    val status = state.status
    val text: String
    val stateText: String
    when (status) {
        is AwayTripStatus.Away -> {
            text = stringResource(R.string.away_banner_active, awayDayText(status.trip.lastDay, state.today))
            stateText = stringResource(R.string.away_banner_state_away)
        }
        is AwayTripStatus.Upcoming -> {
            text = stringResource(
                R.string.away_banner_upcoming,
                awayDayText(status.trip.firstDay, state.today),
                awayDayText(status.trip.lastDay, state.today)
            )
            stateText = stringResource(R.string.away_banner_state_upcoming)
        }
        AwayTripStatus.None -> return
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FlightTakeoff, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { stateDescription = stateText }
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (status is AwayTripStatus.Away) {
                    TextButton(onClick = onPlanVisit) { Text(stringResource(R.string.away_action_plan_visit)) }
                    TextButton(onClick = onImBack) { Text(stringResource(R.string.away_action_back)) }
                }
                if (state.isLastDayAtHome) {
                    TextButton(onClick = onBeforeYouLeave) { Text(stringResource(R.string.away_action_before_leave)) }
                }
            }
        }
    }
}
