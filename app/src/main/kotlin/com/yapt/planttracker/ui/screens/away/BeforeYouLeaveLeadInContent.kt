package com.yapt.planttracker.ui.screens.away

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.ui.components.CareGroupHeader
import com.yapt.planttracker.ui.components.PlantDueRow

/**
 * The days before the last day at home (product ADR-0076): the plants to hold until it, read-only. It takes no
 * callbacks on purpose, so no row can be ticked and nothing can be logged from here.
 */
@Composable
internal fun BeforeYouLeaveLeadInContent(
    state: BeforeYouLeaveUiState.LeadIn,
    contentPadding: PaddingValues = PaddingValues()
) {
    val lastDay = awayDayText(state.lastDayAtHome, state.today)
    if (state.holds.isEmpty()) {
        Text(
            text = stringResource(R.string.before_leave_hold_empty, lastDay),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(contentPadding).padding(16.dp)
        )
        return
    }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "hold-header") {
            Column {
                CareGroupHeader(
                    title = stringResource(R.string.before_leave_hold_title),
                    plantCount = state.holds.size,
                    collapsed = collapsed,
                    onToggle = { collapsed = !collapsed }
                )
                Text(
                    text = stringResource(R.string.before_leave_hold_hint, lastDay),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!collapsed) {
            items(state.holds, key = { "hold-${it.plant.id}" }) { row ->
                PlantDueRow(plant = row.plant, dueDate = row.dueDate)
            }
        }
    }
}
