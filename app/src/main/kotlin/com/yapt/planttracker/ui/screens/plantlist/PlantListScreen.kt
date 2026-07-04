package com.yapt.planttracker.ui.screens.plantlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.ui.components.EmptyStateView
import com.yapt.planttracker.ui.components.PlantCard
import com.yapt.planttracker.ui.components.WaterFeedbackBottomSheet
import com.yapt.planttracker.util.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantListScreen(
    viewModel: PlantListViewModel,
    restoreMessage: String? = null,
    onNavigateToPlant: (Long) -> Unit,
    onNavigateToAdd: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val plantsWithStatus by viewModel.plantsWithStatus.collectAsStateWithLifecycle()
    val plantListItems by viewModel.plantListItems.collectAsStateWithLifecycle()
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val selectedRoom by viewModel.selectedRoom.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val hasUnassignedPlants by viewModel.hasUnassignedPlants.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var waterFeedbackPlant by remember { mutableStateOf<PlantCareStatus?>(null) }
    var liquidFertilizeFeedbackPlant by remember { mutableStateOf<PlantCareStatus?>(null) }
    var pendingIntervalSuggestion by remember { mutableStateOf<QuickWaterSuggestion?>(null) }
    var intervalFieldText by remember(pendingIntervalSuggestion) {
        mutableStateOf(pendingIntervalSuggestion?.suggestedInterval?.toString().orEmpty())
    }
    val parsedInterval = intervalFieldText.toIntOrNull()?.takeIf { it > 0 }

    LaunchedEffect(restoreMessage) {
        if (restoreMessage != null) {
            snackbarHostState.showSnackbar(restoreMessage)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.quickLogEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.quickWaterSuggestion.collect { suggestion ->
            pendingIntervalSuggestion = suggestion
        }
    }

    val movedMsg = stringResource(R.string.snackbar_moved_to_graveyard)
    val undoLabel = stringResource(R.string.snackbar_undo)
    LaunchedEffect(Unit) {
        viewModel.archivedEvent.collect { event ->
            val result = snackbarHostState.showSnackbar(
                message = movedMsg,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoArchive(event.plantId)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_plants)) },
                colors = TopAppBarDefaults.topAppBarColors(),
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.cd_sort_plants))
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            val sortAlpha = stringResource(R.string.sort_alphabetical)
                            val sortAlphaAsc = stringResource(R.string.sort_alphabetical_asc)
                            val sortAlphaDesc = stringResource(R.string.sort_alphabetical_desc)
                            val sortWatering = stringResource(R.string.sort_watering_due)
                            val sortFertilizing = stringResource(R.string.sort_fertilizing_due)
                            val sortRecent = stringResource(R.string.sort_recently_added)
                            val sortBothDue = stringResource(R.string.sort_both_due)
                            SortOption.entries.forEach { option ->
                                val isActive = sortOrder.option == option
                                val label = when (option) {
                                    SortOption.ALPHABETICAL -> if (isActive) {
                                        if (sortOrder.direction == SortDirection.ASC) sortAlphaAsc else sortAlphaDesc
                                    } else sortAlpha
                                    SortOption.WATERING_DUE -> sortWatering
                                    SortOption.FERTILIZING_DUE -> sortFertilizing
                                    SortOption.RECENTLY_ADDED -> sortRecent
                                    SortOption.BOTH_DUE -> sortBothDue
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        viewModel.toggleSort(option)
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_plant)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (rooms.isNotEmpty() || hasUnassignedPlants) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedRoom == null,
                            onClick = { viewModel.selectRoom(null) },
                            label = { Text(stringResource(R.string.time_range_all)) }
                        )
                    }
                    if (hasUnassignedPlants) {
                        item {
                            FilterChip(
                                selected = selectedRoom == PlantListViewModel.UNASSIGNED_ROOM,
                                onClick = { viewModel.selectRoom(PlantListViewModel.UNASSIGNED_ROOM) },
                                label = { Text(stringResource(R.string.filter_unassigned)) }
                            )
                        }
                    }
                    items(rooms) { room ->
                        FilterChip(
                            selected = selectedRoom == room,
                            onClick = { viewModel.selectRoom(room) },
                            label = { Text(room) }
                        )
                    }
                }
            }

            if (plantsWithStatus.isEmpty()) {
                val emptyBothDue = stringResource(R.string.empty_state_both_due)
                val emptyAllAssigned = stringResource(R.string.empty_state_all_assigned)
                val emptyNoPlants = stringResource(R.string.no_plants_yet)
                val emptyMessage = when {
                    sortOrder.option == SortOption.BOTH_DUE -> emptyBothDue
                    selectedRoom == PlantListViewModel.UNASSIGNED_ROOM -> emptyAllAssigned
                    else -> emptyNoPlants
                }
                EmptyStateView(
                    message = emptyMessage,
                    icon = Icons.Filled.LocalFlorist
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(plantListItems) { listItem ->
                        when (listItem) {
                            is PlantListItem.DateHeader -> DateGroupHeader(listItem.bucket)
                            is PlantListItem.PlantRow -> {
                                val status = listItem.status
                                PlantCard(
                                    status = status,
                                    onClick = { onNavigateToPlant(status.plant.id) },
                                    onQuickWater = { waterFeedbackPlant = status },
                                    onQuickFertilize = {
                                        if (status.plant.useLiquidFertilizer) {
                                            liquidFertilizeFeedbackPlant = status
                                        } else {
                                            viewModel.quickLog(status.plant.id, CareType.FERTILIZE)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    waterFeedbackPlant?.let { s ->
        WaterFeedbackBottomSheet(
            plantName = s.plant.name,
            onDismiss = { waterFeedbackPlant = null },
            onLog = { feedback ->
                viewModel.quickWaterWithFeedback(s.plant.id, feedback)
                waterFeedbackPlant = null
            }
        )
    }

    liquidFertilizeFeedbackPlant?.let { s ->
        WaterFeedbackBottomSheet(
            plantName = s.plant.name,
            title = stringResource(R.string.water_fertilize_feedback_sheet_title, s.plant.name),
            onDismiss = { liquidFertilizeFeedbackPlant = null },
            onLog = { feedback ->
                viewModel.quickLiquidFertilizeWithFeedback(s.plant.id, feedback)
                liquidFertilizeFeedbackPlant = null
            }
        )
    }

    pendingIntervalSuggestion?.let { suggestion ->
        val currentInterval = plantsWithStatus
            .firstOrNull { it.plant.id == suggestion.plantId }
            ?.plant?.wateringIntervalDays ?: 0
        AlertDialog(
            onDismissRequest = { pendingIntervalSuggestion = null },
            title = { Text(stringResource(R.string.interval_suggestion_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            R.string.interval_suggestion_body,
                            suggestion.suggestedInterval,
                            currentInterval
                        )
                    )
                    OutlinedTextField(
                        value = intervalFieldText,
                        onValueChange = { intervalFieldText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.interval_suggestion_field_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        parsedInterval?.let {
                            viewModel.applySuggestedIntervalFromList(suggestion.plantId, it)
                        }
                        pendingIntervalSuggestion = null
                    },
                    enabled = parsedInterval != null
                ) {
                    Text(stringResource(R.string.interval_suggestion_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingIntervalSuggestion = null }) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        )
    }
}

@Composable
private fun DateGroupHeader(bucket: DateBucket) {
    val label = when (bucket) {
        DateBucket.Overdue -> stringResource(R.string.date_group_overdue)
        DateBucket.Today -> stringResource(R.string.date_group_today)
        DateBucket.Tomorrow -> stringResource(R.string.date_group_tomorrow)
        is DateBucket.Dated -> DateUtils.formatWeekdayDate(bucket.epochDay)
        DateBucket.Later -> stringResource(R.string.date_group_later)
        DateBucket.NotScheduled -> stringResource(R.string.date_group_not_scheduled)
    }
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
