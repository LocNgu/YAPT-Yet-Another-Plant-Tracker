package com.yapt.planttracker.ui.screens.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.ui.components.CameraPhotoDialogs
import com.yapt.planttracker.ui.components.WateringReasonBottomSheet
import com.yapt.planttracker.ui.components.rememberCameraPhotoState
import com.yapt.planttracker.ui.screens.plantdetail.CareDatePickerBottomSheet
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import com.yapt.planttracker.ui.screens.plantdetail.RescheduleDialogActions
import com.yapt.planttracker.ui.screens.plantdetail.RescheduleWateringDialog
import com.yapt.planttracker.ui.screens.plantdetail.isRescheduleTodayEnabled

private const val TODAY_REPOT_DATE_PICKER_TAG = "today_repot_date_picker"
private const val TODAY_ADD_PLANT_TAG = "today_add_plant"

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onNavigateToPlant: (Long, PlantDetailTab?) -> Unit,
    onNavigateToAdd: () -> Unit,
    onLaunchPhotoCapture: ((Long) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var reasonTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var rescheduleTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var repotTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    var collapsedGroups by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    val readyTasks = (uiState as? TodayUiState.Ready)?.snapshot?.tasks
    val reasonTask = reasonTaskId?.let { id -> readyTasks?.firstOrNull { it.id == id } }
    val rescheduleTask = rescheduleTaskId?.let { id -> readyTasks?.firstOrNull { it.id == id } }
    val repotTask = repotTaskId?.let { id -> readyTasks?.firstOrNull { it.id == id } }
    var pendingPhotoPlantId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingSuggestion by remember { mutableStateOf<QuickWaterSuggestion?>(null) }
    var intervalText by remember(pendingSuggestion) {
        mutableStateOf(pendingSuggestion?.suggestedIntervalEffective?.toString().orEmpty())
    }
    val parsedInterval = intervalText.toIntOrNull()?.takeIf { it > 0 }
    val cameraState = rememberCameraPhotoState(snackbarHostState) { uri ->
        pendingPhotoPlantId?.let { viewModel.savePhoto(it, uri) }
        pendingPhotoPlantId = null
    }

    LaunchedEffect(Unit) {
        viewModel.messageEvent.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.wateringSuggestion.collect { pendingSuggestion = it }
    }
    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { event ->
            when (event) {
                TodayNavigationEvent.AddPlant -> onNavigateToAdd()
                is TodayNavigationEvent.PlantDetail -> onNavigateToPlant(event.plantId, event.tab)
            }
        }
    }

    LaunchedEffect(readyTasks) {
        if (readyTasks != null) {
            val liveIds = readyTasks.mapTo(HashSet()) { it.id }
            reasonTaskId?.let { if (it !in liveIds) reasonTaskId = null }
            rescheduleTaskId?.let { if (it !in liveIds) rescheduleTaskId = null }
            repotTaskId?.let { if (it !in liveIds) repotTaskId = null }
        }
    }

    val actions = TodayTaskActions(
        onOpen = viewModel::openPlant,
        onComplete = { task -> requestCompletion(task, viewModel) { reasonTaskId = task.id } },
        onReschedule = { rescheduleTaskId = it.id },
        onRepot = { repotTaskId = it.id },
        onPhoto = {
            pendingPhotoPlantId = it.plant.id
            onLaunchPhotoCapture?.invoke(it.plant.id) ?: cameraState.launch()
        }
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_tab_today)) }) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                TodayUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                TodayUiState.Error -> TodayErrorState(viewModel::retry)
                is TodayUiState.Ready -> when {
                    state.snapshot.activePlantCount == 0 -> TodayFirstUseState(viewModel::addPlant)
                    state.snapshot.tasks.isEmpty() -> TodayCaughtUpState()
                    else -> TodayCareGrid(
                        tasks = state.snapshot.tasks,
                        gridState = gridState,
                        collapsedGroups = collapsedGroups,
                        onToggleGroup = { collapsedGroups = collapsedGroups.toggled(it) },
                        actions = actions
                    )
                }
            }
        }
    }

    reasonTask?.let { task ->
        WateringReasonBottomSheet(
            plantName = task.plant.name,
            gapRanLong = task.wateringAction?.isGapLong == true,
            title = if (task.kind == TodayCareKind.WATER_AND_FERTILIZE) {
                stringResource(R.string.water_fertilize_feedback_sheet_title, task.plant.name)
            } else {
                stringResource(R.string.water_feedback_sheet_title, task.plant.name)
            },
            onDismiss = { reasonTaskId = null },
            onLog = { reason ->
                if (task.kind == TodayCareKind.WATER) {
                    viewModel.completeWater(task.id, reason)
                } else {
                    viewModel.completeFertilizing(task.id, reason)
                }
                reasonTaskId = null
            }
        )
    }

    rescheduleTask?.let { task ->
        val watering = task.wateringAction
        RescheduleWateringDialog(
            todayEnabled = isRescheduleTodayEnabled(watering?.computedDueAt, watering?.effectiveDueAt),
            computedNextWateringDueAt = watering?.computedDueAt,
            effectiveNextWateringDueAt = watering?.effectiveDueAt,
            actions = RescheduleDialogActions(
                onDismiss = { rescheduleTaskId = null },
                onToday = {
                    viewModel.rescheduleWatering(task.id, System.currentTimeMillis())
                    rescheduleTaskId = null
                },
                onRelativeDate = {
                    viewModel.rescheduleWatering(task.id, it)
                    rescheduleTaskId = null
                },
                onCustomDate = {
                    viewModel.rescheduleWatering(task.id, it)
                    rescheduleTaskId = null
                }
            )
        )
    }

    repotTask?.let { task ->
        CareDatePickerBottomSheet(
            testTag = TODAY_REPOT_DATE_PICKER_TAG,
            onDismiss = { repotTaskId = null },
            onConfirm = {
                viewModel.completeRepotting(task.id, it)
                repotTaskId = null
            }
        )
    }

    pendingSuggestion?.let { suggestion ->
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissSuggestedInterval(suggestion.plantId)
                pendingSuggestion = null
            },
            title = { Text(stringResource(R.string.interval_suggestion_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            R.string.interval_suggestion_body,
                            suggestion.suggestedIntervalEffective,
                            suggestion.currentIntervalEffective
                        )
                    )
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.interval_suggestion_field_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        parsedInterval?.let { interval ->
                            viewModel.applySuggestedInterval(
                                suggestion,
                                interval,
                                suggestion.suggestedBaseInterval.takeIf {
                                    interval == suggestion.suggestedIntervalEffective
                                }
                            )
                        }
                        pendingSuggestion = null
                    },
                    enabled = parsedInterval != null
                ) { Text(stringResource(R.string.interval_suggestion_apply)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissSuggestedInterval(suggestion.plantId)
                    pendingSuggestion = null
                }) { Text(stringResource(R.string.dismiss)) }
            }
        )
    }

    CameraPhotoDialogs(cameraState)
}

@Composable
private fun TodayFirstUseState(onAddPlant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Checklist, contentDescription = null, modifier = Modifier.size(72.dp))
        Text(stringResource(R.string.today_first_use), modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = onAddPlant, modifier = Modifier.testTag(TODAY_ADD_PLANT_TAG)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_plant))
        }
    }
}

@Composable
private fun TodayCaughtUpState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Checklist, contentDescription = null, modifier = Modifier.size(72.dp))
        Text(stringResource(R.string.today_caught_up), modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun TodayErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.today_error))
        TextButton(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.retry))
        }
    }
}

private fun Set<String>.toggled(key: String): Set<String> = if (key in this) this - key else this + key

private fun requestCompletion(
    task: TodayCareTask,
    viewModel: TodayViewModel,
    requestReason: () -> Unit
) {
    when (task.kind) {
        TodayCareKind.WATER -> {
            val action = task.wateringAction
            if (action?.isOnSchedule != false || action.isDormancySpanning) {
                viewModel.completeWater(task.id, null)
            } else {
                requestReason()
            }
        }
        TodayCareKind.WATER_AND_FERTILIZE -> {
            val action = task.wateringAction
            if (action?.isOnSchedule != false || action.isDormancySpanning) {
                viewModel.completeFertilizing(task.id, null)
            } else {
                requestReason()
            }
        }
        TodayCareKind.FERTILIZE -> viewModel.completeFertilizing(task.id, null)
        TodayCareKind.CUSTOM_REMINDER,
        TodayCareKind.ISSUE_TREATMENT -> viewModel.completeCustomReminder(task.id)
        TodayCareKind.REPOT,
        TodayCareKind.PHOTO -> Unit
    }
}
