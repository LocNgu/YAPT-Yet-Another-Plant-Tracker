package com.yapt.planttracker.ui.screens.away

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import java.time.LocalDate

/** The checklist that confirms the previous visit (product ADR-0065): every plant ticked, untick what was skipped. */
@Composable
internal fun ConfirmVisitContent(
    state: PlanVisitUiState.Confirm,
    today: LocalDate,
    actions: ConfirmVisitActions,
    contentPadding: PaddingValues = PaddingValues()
) {
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.sitter_confirm_heading, awayWeekdayDayText(state.visit.day, today)),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        stringResource(R.string.sitter_confirm_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.rows, key = { it.plant.id }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = row.ticked,
                            role = Role.Checkbox,
                            onValueChange = { actions.onToggle(row.plant.id) }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = row.ticked, onCheckedChange = null)
                    Text(
                        row.plant.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = actions.onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sitter_confirm_continue))
            }
            TextButton(onClick = actions.onDidNotHappen, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sitter_confirm_did_not_happen))
            }
        }
    }
}
