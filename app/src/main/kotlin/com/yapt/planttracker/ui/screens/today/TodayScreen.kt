@file:Suppress("TooManyFunctions")

package com.yapt.planttracker.ui.screens.today

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayPlantGroup
import com.yapt.planttracker.domain.today.TodayTaskBucket
import com.yapt.planttracker.domain.today.plantSections
import com.yapt.planttracker.domain.today.taskSections
import com.yapt.planttracker.ui.components.CameraPhotoDialogs
import com.yapt.planttracker.ui.components.PlantPhoto
import com.yapt.planttracker.ui.components.WateringReasonBottomSheet
import com.yapt.planttracker.ui.components.rememberCameraPhotoState
import com.yapt.planttracker.ui.screens.plantdetail.CareDatePickerBottomSheet
import com.yapt.planttracker.ui.screens.plantdetail.RescheduleDialogActions
import com.yapt.planttracker.ui.screens.plantdetail.RescheduleWateringDialog
import com.yapt.planttracker.ui.screens.plantdetail.isRescheduleTodayEnabled
import com.yapt.planttracker.util.DateUtils

private const val TODAY_REPOT_DATE_PICKER_TAG = "today_repot_date_picker"

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onNavigateToPlant: (Long) -> Unit,
    onNavigateToAdd: () -> Unit,
    onSelectionModeChanged: (Boolean) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTaskIds by viewModel.selectedTaskIds.collectAsStateWithLifecycle()
    val selectionMode = selectedTaskIds.isNotEmpty()
    val snackbarHostState = remember { SnackbarHostState() }
    var reasonTask by remember { mutableStateOf<TodayCareTask?>(null) }
    var rescheduleTask by remember { mutableStateOf<TodayCareTask?>(null) }
    var repotTask by remember { mutableStateOf<TodayCareTask?>(null) }
    var pendingPhotoTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSuggestion by remember { mutableStateOf<QuickWaterSuggestion?>(null) }
    var intervalText by remember(pendingSuggestion) {
        mutableStateOf(pendingSuggestion?.suggestedIntervalEffective?.toString().orEmpty())
    }
    val parsedInterval = intervalText.toIntOrNull()?.takeIf { it > 0 }
    val cameraState = rememberCameraPhotoState(snackbarHostState) { uri ->
        pendingPhotoTaskId?.let { viewModel.savePhoto(it, uri) }
        pendingPhotoTaskId = null
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
                is TodayNavigationEvent.PlantDetail -> onNavigateToPlant(event.plantId)
            }
        }
    }

    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }
    LaunchedEffect(selectionMode) { onSelectionModeChanged(selectionMode) }
    DisposableEffect(Unit) { onDispose { onSelectionModeChanged(false) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) {
                            stringResource(R.string.today_selected_count, selectedTaskIds.size)
                        } else {
                            stringResource(R.string.nav_tab_today)
                        }
                    )
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, stringResource(R.string.cd_bulk_clear_selection))
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = viewModel::selectAll) {
                            Icon(Icons.Filled.DoneAll, stringResource(R.string.cd_bulk_select_all))
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (selectionMode) {
                Button(
                    onClick = viewModel::completeSelected,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Text(stringResource(R.string.today_complete_selected))
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                TodayUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                TodayUiState.Error -> TodayErrorState(viewModel::retry)
                is TodayUiState.Ready -> when {
                    state.snapshot.activePlantCount == 0 -> TodayFirstUseState(viewModel::addPlant)
                    state.snapshot.tasks.isEmpty() -> TodayCaughtUpState()
                    state.groupByPlant -> GroupedTodayList(
                        tasks = state.snapshot.tasks,
                        selectedTaskIds = selectedTaskIds,
                        onTogglePlant = viewModel::togglePlantSelection,
                        actions = TodayTaskActions(
                            onOpen = viewModel::openPlant,
                            onToggleSelection = viewModel::toggleTaskSelection,
                            onComplete = { task -> requestCompletion(task, viewModel) { reasonTask = task } },
                            onReschedule = { rescheduleTask = it },
                            onRepot = { repotTask = it },
                            onPhoto = {
                                pendingPhotoTaskId = it.id
                                cameraState.launch()
                            }
                        )
                    )
                    else -> TaskTodayList(
                        tasks = state.snapshot.tasks,
                        selectedTaskIds = selectedTaskIds,
                        actions = TodayTaskActions(
                            onOpen = viewModel::openPlant,
                            onToggleSelection = viewModel::toggleTaskSelection,
                            onComplete = { task -> requestCompletion(task, viewModel) { reasonTask = task } },
                            onReschedule = { rescheduleTask = it },
                            onRepot = { repotTask = it },
                            onPhoto = {
                                pendingPhotoTaskId = it.id
                                cameraState.launch()
                            }
                        )
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
            onDismiss = { reasonTask = null },
            onLog = { reason ->
                if (task.kind == TodayCareKind.WATER) {
                    viewModel.completeWater(task.id, reason)
                } else {
                    viewModel.completeFertilizing(task.id, reason)
                }
                reasonTask = null
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
                onDismiss = { rescheduleTask = null },
                onToday = {
                    viewModel.rescheduleWatering(task.id, System.currentTimeMillis())
                    rescheduleTask = null
                },
                onRelativeDate = {
                    viewModel.rescheduleWatering(task.id, it)
                    rescheduleTask = null
                },
                onCustomDate = {
                    viewModel.rescheduleWatering(task.id, it)
                    rescheduleTask = null
                }
            )
        )
    }

    repotTask?.let { task ->
        CareDatePickerBottomSheet(
            testTag = TODAY_REPOT_DATE_PICKER_TAG,
            onDismiss = { repotTask = null },
            onConfirm = {
                viewModel.completeRepotting(task.id, it)
                repotTask = null
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

private data class TodayTaskActions(
    val onOpen: (Long) -> Unit,
    val onToggleSelection: (String) -> Unit,
    val onComplete: (TodayCareTask) -> Unit,
    val onReschedule: (TodayCareTask) -> Unit,
    val onRepot: (TodayCareTask) -> Unit,
    val onPhoto: (TodayCareTask) -> Unit
)

@Composable
private fun TaskTodayList(
    tasks: List<TodayCareTask>,
    selectedTaskIds: Set<String>,
    actions: TodayTaskActions
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        for (section in taskSections(tasks)) {
            item(key = "header-${section.bucket}") { TodaySectionHeader(section.bucket) }
            items(section.tasks, key = { it.id }) { task ->
                TodayTaskRow(task, task.id in selectedTaskIds, actions, showSelection = true)
            }
        }
    }
}

@Composable
private fun GroupedTodayList(
    tasks: List<TodayCareTask>,
    selectedTaskIds: Set<String>,
    onTogglePlant: (Long) -> Unit,
    actions: TodayTaskActions
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        for (section in plantSections(tasks)) {
            item(key = "group-header-${section.bucket}") { TodaySectionHeader(section.bucket) }
            items(section.groups, key = { "plant-${it.plant.id}" }) { group ->
                TodayPlantGroupCard(group, selectedTaskIds, onTogglePlant, actions)
            }
        }
    }
}

@Composable
private fun TodayPlantGroupCard(
    group: TodayPlantGroup,
    selectedTaskIds: Set<String>,
    onTogglePlant: (Long) -> Unit,
    actions: TodayTaskActions
) {
    val eligibleIds = group.tasks.filter { it.isBulkEligible }.map { it.id }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlantPhoto(group.plant.coverPhotoUri, 44.dp)
            Spacer(Modifier.width(12.dp))
            Text(group.plant.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (eligibleIds.isNotEmpty()) {
                val description = stringResource(R.string.today_select_plant_tasks, group.plant.name)
                Checkbox(
                    checked = eligibleIds.all { it in selectedTaskIds },
                    onCheckedChange = { onTogglePlant(group.plant.id) },
                    modifier = Modifier.semantics { contentDescription = description }
                )
            }
        }
        HorizontalDivider()
        for (task in group.tasks) {
            TodayTaskRow(task, task.id in selectedTaskIds, actions, showSelection = false)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodayTaskRow(
    task: TodayCareTask,
    selected: Boolean,
    actions: TodayTaskActions,
    showSelection: Boolean
) {
    val selectDescription = stringResource(R.string.today_select_task, taskTitle(task))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { actions.onOpen(task.plant.id) },
                onLongClick = { if (task.isBulkEligible) actions.onToggleSelection(task.id) }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showSelection && task.isBulkEligible) {
            Checkbox(
                checked = selected,
                onCheckedChange = { actions.onToggleSelection(task.id) },
                modifier = Modifier.semantics { contentDescription = selectDescription }
            )
            Spacer(Modifier.width(8.dp))
        }
        if (showSelection) {
            PlantPhoto(task.plant.coverPhotoUri, 40.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(taskTitle(task), style = MaterialTheme.typography.titleSmall)
            if (showSelection) {
                Text(task.plant.name, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                DateUtils.formatRelative(task.dueAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TodayTaskButtons(task, actions)
    }
}

@Composable
private fun TodayTaskButtons(task: TodayCareTask, actions: TodayTaskActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            when (task.kind) {
                TodayCareKind.REPOT -> actions.onRepot(task)
                TodayCareKind.PHOTO -> actions.onPhoto(task)
                else -> actions.onComplete(task)
            }
        }) {
            Text(
                when (task.kind) {
                    TodayCareKind.WATER -> stringResource(R.string.today_action_water)
                    TodayCareKind.FERTILIZE -> stringResource(R.string.today_action_fertilize)
                    TodayCareKind.WATER_AND_FERTILIZE -> stringResource(R.string.today_action_water_fertilize)
                    TodayCareKind.REPOT -> stringResource(R.string.today_action_repot)
                    TodayCareKind.CUSTOM_REMINDER,
                    TodayCareKind.ISSUE_TREATMENT -> stringResource(R.string.today_action_done)
                    TodayCareKind.PHOTO -> stringResource(R.string.today_action_photo)
                }
            )
        }
        if (task.kind == TodayCareKind.WATER || task.kind == TodayCareKind.WATER_AND_FERTILIZE) {
            IconButton(onClick = { actions.onReschedule(task) }) {
                Icon(Icons.Filled.Schedule, stringResource(R.string.reschedule_watering_title))
            }
        }
    }
}

@Composable
private fun taskTitle(task: TodayCareTask): String = when (task.kind) {
    TodayCareKind.WATER -> stringResource(R.string.today_task_water)
    TodayCareKind.FERTILIZE -> stringResource(R.string.today_task_fertilize)
    TodayCareKind.WATER_AND_FERTILIZE -> stringResource(R.string.today_task_water_fertilize)
    TodayCareKind.REPOT -> stringResource(R.string.today_task_repot)
    TodayCareKind.CUSTOM_REMINDER -> task.customReminder?.name.orEmpty()
    TodayCareKind.ISSUE_TREATMENT -> stringResource(
        R.string.today_task_treatment,
        task.issueName ?: task.customReminder?.name.orEmpty()
    )
    TodayCareKind.PHOTO -> stringResource(R.string.today_task_photo)
}

@Composable
private fun TodaySectionHeader(bucket: TodayTaskBucket) {
    val title = when (bucket) {
        TodayTaskBucket.Overdue -> stringResource(R.string.date_group_overdue)
        TodayTaskBucket.Today -> stringResource(R.string.date_group_today)
        is TodayTaskBucket.Upcoming -> DateUtils.formatWeekdayDate(bucket.epochDay)
    }
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
    )
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
        Button(onClick = onAddPlant) {
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
