package com.yapt.planttracker.ui.screens.repotting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.repotting.RepottingOverviewThreshold
import com.yapt.planttracker.domain.schedule.RepotPlanSeason
import com.yapt.planttracker.ui.components.EmptyStateView
import com.yapt.planttracker.ui.screens.plantdetail.CareDatePickerBottomSheet
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import com.yapt.planttracker.ui.screens.plantdetail.RepotPlanSeasonDialog
import kotlinx.coroutines.flow.Flow

internal const val REPOTTING_OVERVIEW_REPOT_DATE_PICKER_TAG = "repotting_overview_repot_date_picker"

@Composable
fun RepottingOverviewScreen(
    viewModel: RepottingOverviewViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPlant: (plantId: Long, tab: PlantDetailTab) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var repotPlantId by rememberSaveable { mutableStateOf<Long?>(null) }
    var planPlantId by rememberSaveable { mutableStateOf<Long?>(null) }
    val plantsById = remember(uiState) { (uiState as? RepottingOverviewUiState.Ready)?.plantsById().orEmpty() }

    RepottingEventSnackbars(viewModel.events, snackbarHostState)
    // A prompt for a plant that just left the page (repotted, cleared, rolled past midnight) is moot.
    LaunchedEffect(uiState) {
        if (uiState is RepottingOverviewUiState.Ready) {
            if (repotPlantId !in plantsById) repotPlantId = null
            if (planPlantId !in plantsById) planPlantId = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { RepottingOverviewTopBar(onNavigateBack) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                RepottingOverviewUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is RepottingOverviewUiState.Ready -> RepottingOverviewBody(
                    state = state,
                    onSelectThreshold = viewModel::selectThreshold,
                    actions = RepottingRowActions(
                        onOpen = { onNavigateToPlant(it, PlantDetailTab.REPOT) },
                        onRepot = { repotPlantId = it },
                        onPlan = { planPlantId = it },
                        onClearPlan = viewModel::clearPlan
                    )
                )
            }
        }
    }

    RepottingPrompts(
        repotPlant = repotPlantId?.let(plantsById::get),
        planPlant = planPlantId?.let(plantsById::get),
        actions = RepottingPromptActions(
            onRepot = { plant, loggedAt ->
                viewModel.repot(plant.id, loggedAt)
                repotPlantId = null
            },
            onPlan = { plant, season ->
                viewModel.planRepot(plant.id, season)
                planPlantId = null
            },
            onDismissRepot = { repotPlantId = null },
            onDismissPlan = { planPlantId = null }
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepottingOverviewTopBar(onNavigateBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.repotting_overview_title)) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back)
                )
            }
        }
    )
}

private fun RepottingOverviewUiState.Ready.plantsById(): Map<Long, Plant> =
    (overview.planned.map { it.plant } + overview.items.map { it.plant }).associateBy { it.id }

@Composable
private fun RepottingEventSnackbars(
    events: Flow<RepottingOverviewViewModel.Event>,
    snackbarHostState: SnackbarHostState
) {
    val resources = LocalContext.current.resources
    LaunchedEffect(events) {
        events.collect { event ->
            val message = when (event) {
                is RepottingOverviewViewModel.Event.Message -> event.text
                is RepottingOverviewViewModel.Event.PlanSaved ->
                    resources.getString(R.string.repotting_overview_plan_saved, event.plantName)
                is RepottingOverviewViewModel.Event.PlanCleared ->
                    resources.getString(R.string.repotting_overview_plan_cleared, event.plantName)
                RepottingOverviewViewModel.Event.ActionFailed ->
                    resources.getString(R.string.repotting_overview_action_failed)
            }
            snackbarHostState.showSnackbar(message)
        }
    }
}

private data class RepottingPromptActions(
    val onRepot: (Plant, Long) -> Unit,
    val onPlan: (Plant, RepotPlanSeason) -> Unit,
    val onDismissRepot: () -> Unit,
    val onDismissPlan: () -> Unit
)

// The existing repot date prompt and season picker, reused as they are on Care and Plant Detail.
@Composable
private fun RepottingPrompts(repotPlant: Plant?, planPlant: Plant?, actions: RepottingPromptActions) {
    if (repotPlant != null) {
        CareDatePickerBottomSheet(
            testTag = REPOTTING_OVERVIEW_REPOT_DATE_PICKER_TAG,
            onDismiss = actions.onDismissRepot,
            onConfirm = { loggedAt -> actions.onRepot(repotPlant, loggedAt) }
        )
    }
    if (planPlant != null) {
        RepotPlanSeasonDialog(
            onSelect = { season -> actions.onPlan(planPlant, season) },
            onDismiss = actions.onDismissPlan
        )
    }
}

@Composable
private fun RepottingOverviewBody(
    state: RepottingOverviewUiState.Ready,
    onSelectThreshold: (RepottingOverviewThreshold) -> Unit,
    actions: RepottingRowActions
) {
    if (!state.hasActivePlants) {
        EmptyStateView(
            message = stringResource(R.string.repotting_overview_no_plants),
            icon = Icons.Filled.LocalFlorist
        )
        return
    }
    RepottingThresholdChips(selected = state.threshold, onSelect = onSelectThreshold)
    if (state.overview.planned.isEmpty() && state.overview.items.isEmpty()) {
        EmptyStateView(
            message = stringResource(R.string.repotting_overview_nothing_here),
            icon = Icons.Filled.LocalFlorist
        )
    } else {
        RepottingOverviewList(
            threshold = state.threshold,
            planned = state.overview.planned,
            items = state.overview.items,
            actions = actions
        )
    }
}
