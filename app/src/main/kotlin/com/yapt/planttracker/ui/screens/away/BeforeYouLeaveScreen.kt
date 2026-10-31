package com.yapt.planttracker.ui.screens.away

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.away.AWAY_BANNER_LEAD_DAYS
import com.yapt.planttracker.ui.components.CareGroupHeader
import com.yapt.planttracker.ui.components.PlantDueRow

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
            BeforeYouLeaveUiState.NotAvailable -> {
                val leadDays = AWAY_BANNER_LEAD_DAYS.toInt()
                Text(
                    text = pluralStringResource(R.plurals.before_leave_not_available, leadDays, leadDays),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(padding).padding(16.dp)
                )
            }
            is BeforeYouLeaveUiState.LeadIn -> BeforeYouLeaveLeadInContent(state = current, contentPadding = padding)
            is BeforeYouLeaveUiState.Ready -> BeforeYouLeaveContent(
                state = current,
                onToggle = viewModel::toggle,
                onLog = { viewModel.logTicked() },
                contentPadding = padding
            )
        }
    }
}

private fun Set<Int>.toggled(key: Int): Set<Int> = if (key in this) this - key else this + key

@Composable
internal fun BeforeYouLeaveContent(
    state: BeforeYouLeaveUiState.Ready,
    onToggle: (plantId: Long) -> Unit,
    onLog: () -> Unit,
    contentPadding: PaddingValues = PaddingValues()
) {
    val tickedCount = state.tickedPlants.size
    var collapsedSections by rememberSaveable { mutableStateOf(emptySet<Int>()) }
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                collapsed = R.string.before_leave_water_today in collapsedSections,
                onToggleSection = { collapsedSections = collapsedSections.toggled(R.string.before_leave_water_today) },
                onToggle = onToggle
            )
            plantSection(
                title = R.string.before_leave_check_soil,
                hint = R.string.before_leave_check_soil_hint,
                rows = state.checkTheSoil,
                collapsed = R.string.before_leave_check_soil in collapsedSections,
                onToggleSection = { collapsedSections = collapsedSections.toggled(R.string.before_leave_check_soil) },
                onToggle = onToggle
            )
            item {
                Text(
                    pluralStringResource(R.plurals.before_leave_can_wait, state.canWaitCount, state.canWaitCount),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
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

@Suppress("LongParameterList")
private fun LazyListScope.plantSection(
    title: Int,
    hint: Int?,
    rows: List<BeforeYouLeaveRow>,
    collapsed: Boolean,
    onToggleSection: () -> Unit,
    onToggle: (Long) -> Unit
) {
    if (rows.isEmpty()) return
    item(key = "header-$title") {
        Column {
            CareGroupHeader(
                title = stringResource(title),
                plantCount = rows.size,
                collapsed = collapsed,
                onToggle = onToggleSection
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
    if (collapsed) return
    items(rows, key = { "$title-${it.plant.id}" }) { row ->
        PlantDueRow(
            plant = row.plant,
            dueDate = row.dueDate,
            checked = row.ticked,
            onCheckedChange = { onToggle(row.plant.id) }
        )
    }
}
