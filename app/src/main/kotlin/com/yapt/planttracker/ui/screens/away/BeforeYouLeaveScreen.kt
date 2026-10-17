package com.yapt.planttracker.ui.screens.away

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.ui.util.relativeDateText
import com.yapt.planttracker.util.toStartOfDayMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeforeYouLeaveScreen(viewModel: BeforeYouLeaveViewModel, onNavigateBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BeforeYouLeaveEvent.Logged -> snackbarHostState.showSnackbar(context.beforeYouLeaveLoggedText(event))
            }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.before_leave_title)) },
                navigationIcon = { AwayBackNavigationIcon(onNavigateBack) }
            )
        }
    ) { padding ->
        when (val current = state) {
            BeforeYouLeaveUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            BeforeYouLeaveUiState.NotAvailable -> Text(
                text = stringResource(R.string.before_leave_not_available),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(padding).padding(16.dp)
            )
            is BeforeYouLeaveUiState.Ready -> BeforeYouLeaveContent(
                state = current,
                onToggle = viewModel::toggle,
                onLog = { viewModel.logTicked() },
                contentPadding = padding
            )
        }
    }
}

@Composable
internal fun BeforeYouLeaveContent(
    state: BeforeYouLeaveUiState.Ready,
    onToggle: (plantId: Long) -> Unit,
    onLog: () -> Unit,
    contentPadding: PaddingValues = PaddingValues()
) {
    val tickedCount = state.tickedPlants.size
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.before_leave_intro, awayDayText(state.firstVisit, state.today)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            plantSection(
                title = R.string.before_leave_water_today,
                hint = null,
                rows = state.waterToday,
                onToggle = onToggle
            )
            plantSection(
                title = R.string.before_leave_check_soil,
                hint = R.string.before_leave_check_soil_hint,
                rows = state.checkTheSoil,
                onToggle = onToggle
            )
            item {
                Text(
                    pluralStringResource(R.plurals.before_leave_can_wait, state.canWaitCount, state.canWaitCount),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
        Button(
            onClick = onLog,
            enabled = tickedCount > 0,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text(pluralStringResource(R.plurals.before_leave_log_action, tickedCount, tickedCount)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.plantSection(
    title: Int,
    hint: Int?,
    rows: List<BeforeYouLeaveRow>,
    onToggle: (Long) -> Unit
) {
    if (rows.isEmpty()) return
    item(key = "header-$title") {
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(
                stringResource(R.string.before_leave_section_title, stringResource(title), rows.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (hint != null) {
                Text(
                    stringResource(hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    items(rows, key = { "$title-${it.plant.id}" }) { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = row.ticked, role = Role.Checkbox, onValueChange = { onToggle(row.plant.id) })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = row.ticked, onCheckedChange = null)
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(row.plant.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    relativeDateText(row.dueDate.toStartOfDayMillis()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
