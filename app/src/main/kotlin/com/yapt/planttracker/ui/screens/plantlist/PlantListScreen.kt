package com.yapt.planttracker.ui.screens.plantlist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.ui.components.BulkActionBar
import com.yapt.planttracker.ui.components.CameraPhotoDialogs
import com.yapt.planttracker.ui.components.EmptyStateView
import com.yapt.planttracker.ui.components.PhotoReminderDialog
import com.yapt.planttracker.ui.components.PlantCard
import com.yapt.planttracker.ui.components.WateringReasonBottomSheet
import com.yapt.planttracker.ui.components.rememberCameraPhotoState
import com.yapt.planttracker.ui.components.yaptFilterChipColors
import com.yapt.planttracker.util.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantListScreen(
    viewModel: PlantListViewModel,
    restoreMessage: String? = null,
    onNavigateToPlant: (Long) -> Unit,
    onNavigateToAdd: () -> Unit,
    onSelectionModeChanged: (Boolean) -> Unit = {}
) {
    val plantsWithStatus by viewModel.plantsWithStatus.collectAsStateWithLifecycle()
    val plantListItems by viewModel.plantListItems.collectAsStateWithLifecycle()
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val selectedRoom by viewModel.selectedRoom.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val hasUnassignedPlants by viewModel.hasUnassignedPlants.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
    val searchQueryText = viewModel.searchQueryText
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var waterFeedbackPlant by remember { mutableStateOf<PlantCareStatus?>(null) }
    var liquidFertilizeFeedbackPlant by remember { mutableStateOf<PlantCareStatus?>(null) }
    var pendingIntervalSuggestion by remember { mutableStateOf<QuickWaterSuggestion?>(null) }
    // #644: pre-fill from the effective (seasonally-converted) value, matching the "Suggested: N days"
    // sentence in the dialog below — not the raw base-space suggestedInterval.
    var intervalFieldText by remember(pendingIntervalSuggestion) {
        mutableStateOf(pendingIntervalSuggestion?.suggestedIntervalEffective?.toString().orEmpty())
    }
    val parsedInterval = intervalFieldText.toIntOrNull()?.takeIf { it > 0 }
    val photoReminderRequest by viewModel.photoReminderRequest.collectAsStateWithLifecycle()
    val selectedPlantIds by viewModel.selectedPlantIds.collectAsStateWithLifecycle()
    val selectionMode = selectedPlantIds.isNotEmpty()
    // Count shown in the bulk bar; holds the last non-zero value so the bar doesn't flash
    // "Apply to 0 plants" for a frame while it slides out after an action clears the selection.
    var bulkBarCount by remember { mutableStateOf(0) }
    LaunchedEffect(selectedPlantIds.size) {
        if (selectedPlantIds.isNotEmpty()) bulkBarCount = selectedPlantIds.size
    }
    var showBulkArchiveConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var reminderPlantId by rememberSaveable { mutableStateOf<Long?>(null) }
    val reminderCameraState = rememberCameraPhotoState(snackbarHostState) { uri ->
        reminderPlantId?.let { viewModel.saveReminderPhoto(it, uri) }
        reminderPlantId = null
        viewModel.dismissPhotoReminder()
    }

    LaunchedEffect(restoreMessage) {
        if (restoreMessage != null) {
            snackbarHostState.showSnackbar(restoreMessage)
        }
    }

    LaunchedEffect(viewModel.quickLogEvent) {
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

    LaunchedEffect(Unit) {
        viewModel.bulkArchivedEvent.collect { event ->
            val message = context.resources.getQuantityString(
                R.plurals.bulk_moved_to_graveyard,
                event.plantIds.size,
                event.plantIds.size
            )
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoBulkArchive(event.plantIds)
            }
        }
    }

    // While selecting, the system back button exits selection mode instead of leaving the screen.
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    // Back while searching (not selecting) closes search and clears the query (#512). Selection mode
    // takes priority when both are true — the handler above stays enabled and this one doesn't, so
    // Back exits selection first and search is restored (still open, same query) once selection ends.
    BackHandler(enabled = isSearchActive && !selectionMode) { viewModel.closeSearch() }

    // Entering selection mode hides the keyboard — the search field (if open) is about to be replaced
    // by the contextual selection bar (#512).
    LaunchedEffect(selectionMode) {
        if (selectionMode) keyboardController?.hide()
    }

    // Report selection state up so the host can hide the Plants/Calendar bottom nav while selecting,
    // giving the bulk action bar (and the list above it) more room. Reset on dispose so the nav
    // never stays hidden if this screen leaves composition mid-selection.
    LaunchedEffect(selectionMode) { onSelectionModeChanged(selectionMode) }
    DisposableEffect(Unit) { onDispose { onSelectionModeChanged(false) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            when {
                selectionMode -> {
                    TopAppBar(
                        title = {
                            Text(
                                pluralStringResource(
                                    R.plurals.bulk_selected_count,
                                    selectedPlantIds.size,
                                    selectedPlantIds.size
                                )
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(),
                        navigationIcon = {
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.cd_bulk_clear_selection)
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.selectAll() }) {
                                Icon(
                                    Icons.Filled.DoneAll,
                                    contentDescription = stringResource(R.string.cd_bulk_select_all)
                                )
                            }
                        }
                    )
                }
                isSearchActive -> {
                    val searchFocusRequester = remember { FocusRequester() }
                    // Fresh each time this branch is (re-)entered: a genuine tap of the search icon
                    // and returning from Plant Detail with search already open both re-enter this
                    // branch, but consumeSearchAutoFocus() only returns true for the former (#512).
                    LaunchedEffect(Unit) {
                        if (viewModel.consumeSearchAutoFocus()) {
                            searchFocusRequester.requestFocus()
                        }
                    }
                    val searchFieldDescription = stringResource(R.string.search_plants_placeholder)
                    TopAppBar(
                        title = {
                            TextField(
                                value = searchQueryText,
                                onValueChange = viewModel::setSearchQuery,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester)
                                    .semantics { contentDescription = searchFieldDescription },
                                placeholder = { Text(searchFieldDescription) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                ),
                                trailingIcon = if (searchQueryText.isNotEmpty()) {
                                    {
                                        IconButton(
                                            onClick = {
                                                // Stays in search mode with focus/keyboard active (#512).
                                                viewModel.clearSearchQuery()
                                                searchFocusRequester.requestFocus()
                                            }
                                        ) {
                                            Icon(
                                                Icons.Filled.Clear,
                                                contentDescription = stringResource(R.string.cd_clear_search)
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                // Filtering is already live as-you-type; Search just hides the keyboard (#512).
                                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(),
                        navigationIcon = {
                            IconButton(onClick = { viewModel.closeSearch() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_back)
                                )
                            }
                        },
                        actions = {
                            SortMenuAction(
                                expanded = sortMenuExpanded,
                                onExpandedChange = { sortMenuExpanded = it },
                                sortOrder = sortOrder,
                                onToggleSort = viewModel::toggleSort
                            )
                        }
                    )
                }
                else -> {
                    TopAppBar(
                        title = { Text(stringResource(R.string.my_plants)) },
                        colors = TopAppBarDefaults.topAppBarColors(),
                        actions = {
                            // Mirrors the room-chip row's own conditional visibility (#512) — both are
                            // true exactly when the user has >= 1 active plant. Search mode, once
                            // opened, stays open regardless of this condition (see the branch above).
                            if (rooms.isNotEmpty() || hasUnassignedPlants) {
                                IconButton(onClick = { viewModel.openSearch() }) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = stringResource(R.string.cd_search_plants)
                                    )
                                }
                            }
                            SortMenuAction(
                                expanded = sortMenuExpanded,
                                onExpandedChange = { sortMenuExpanded = it },
                                sortOrder = sortOrder,
                                onToggleSort = viewModel::toggleSort
                            )
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            // Hidden while selecting — the bulk action bar occupies the bottom instead.
            if (!selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = onNavigateToAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add_plant)) }
                )
            }
        },
        bottomBar = {
            // Slides up the moment the first plant is marked and stays up (non-modal) so the
            // list above remains interactive for adding/removing more plants before acting.
            AnimatedVisibility(
                visible = selectionMode,
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                BulkActionBar(
                    selectedCount = bulkBarCount,
                    onCareAction = { careType -> viewModel.bulkLog(careType) },
                    onMoveToGraveyard = { showBulkArchiveConfirm = true }
                )
            }
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
                            label = { Text(stringResource(R.string.time_range_all)) },
                            colors = yaptFilterChipColors()
                        )
                    }
                    if (hasUnassignedPlants) {
                        item {
                            FilterChip(
                                selected = selectedRoom == PlantListViewModel.UNASSIGNED_ROOM,
                                onClick = { viewModel.selectRoom(PlantListViewModel.UNASSIGNED_ROOM) },
                                label = { Text(stringResource(R.string.filter_unassigned)) },
                                colors = yaptFilterChipColors()
                            )
                        }
                    }
                    items(rooms, key = { room -> "room-$room" }) { room ->
                        FilterChip(
                            selected = selectedRoom == room,
                            onClick = { viewModel.selectRoom(room) },
                            label = { Text(room) },
                            colors = yaptFilterChipColors()
                        )
                    }
                }
            }

            val trimmedSearchQuery = searchQueryText.trim()
            if (plantsWithStatus.isEmpty() && trimmedSearchQuery.isNotEmpty()) {
                // Dedicated "no matches" empty state takes priority over every sort/room-based
                // message below (#512) — a non-blank query with zero results is always this state,
                // regardless of which sort option or room filter is also active.
                EmptyStateView(
                    message = stringResource(R.string.empty_state_no_search_matches, trimmedSearchQuery),
                    icon = Icons.Filled.SearchOff
                )
            } else if (plantsWithStatus.isEmpty()) {
                val emptyBothDue = stringResource(R.string.empty_state_both_due)
                val emptyCaredToday = stringResource(R.string.empty_state_cared_today)
                val emptyActiveIssues = stringResource(R.string.empty_state_active_issues)
                val emptyAllAssigned = stringResource(R.string.empty_state_all_assigned)
                val emptyNoPlants = stringResource(R.string.no_plants_yet)
                val emptyMessage = when {
                    sortOrder.option == SortOption.BOTH_DUE -> emptyBothDue
                    sortOrder.option == SortOption.CARED_FOR_TODAY -> emptyCaredToday
                    sortOrder.option == SortOption.ACTIVE_ISSUES -> emptyActiveIssues
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
                    items(
                        plantListItems,
                        key = { listItem ->
                            when (listItem) {
                                is PlantListItem.DateHeader -> "header-${listItem.bucket}"
                                is PlantListItem.PlantRow -> "plant-${listItem.status.plant.id}"
                            }
                        }
                    ) { listItem ->
                        when (listItem) {
                            is PlantListItem.DateHeader -> DateGroupHeader(listItem.bucket)
                            is PlantListItem.PlantRow -> {
                                val status = listItem.status
                                PlantCard(
                                    status = status,
                                    selectionMode = selectionMode,
                                    selected = status.plant.id in selectedPlantIds,
                                    onClick = { onNavigateToPlant(status.plant.id) },
                                    onLongClick = { viewModel.toggleSelection(status.plant.id) },
                                    onToggleSelect = { viewModel.toggleSelection(status.plant.id) },
                                    // #586 fast path: only an off-schedule watering asks why. A gap
                                    // that overlaps the plant's dormancy window skips the prompt too
                                    // (#699/#761, product ADR-0044) — see CalendarScreen's requestWater
                                    // for the full rationale (a persisted answer could poison a later
                                    // correctionStreak() window even though this observation is
                                    // excluded from base learning either way).
                                    onQuickWater = {
                                        if (status.isWateringOnSchedule || status.isWateringGapDormancySpanning) {
                                            viewModel.quickWater(status.plant.id, reason = null)
                                        } else {
                                            waterFeedbackPlant = status
                                        }
                                    },
                                    onQuickFertilize = {
                                        when {
                                            !status.plant.useLiquidFertilizer ->
                                                viewModel.quickLog(status.plant.id, CareType.FERTILIZE)
                                            status.isWateringOnSchedule || status.isWateringGapDormancySpanning ->
                                                viewModel.quickLiquidFertilize(status.plant.id, reason = null)
                                            else -> liquidFertilizeFeedbackPlant = status
                                        }
                                    }
                                )
                            }
                        }
                    }
                    item(key = "bottom-spacer") { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    waterFeedbackPlant?.let { s ->
        WateringReasonBottomSheet(
            plantName = s.plant.name,
            gapRanLong = s.isWateringGapLong,
            onDismiss = { waterFeedbackPlant = null },
            onLog = { reason ->
                viewModel.quickWater(s.plant.id, reason)
                waterFeedbackPlant = null
            }
        )
    }

    liquidFertilizeFeedbackPlant?.let { s ->
        WateringReasonBottomSheet(
            plantName = s.plant.name,
            gapRanLong = s.isWateringGapLong,
            title = stringResource(R.string.water_fertilize_feedback_sheet_title, s.plant.name),
            onDismiss = { liquidFertilizeFeedbackPlant = null },
            onLog = { reason ->
                viewModel.quickLiquidFertilize(s.plant.id, reason)
                liquidFertilizeFeedbackPlant = null
            }
        )
    }

    pendingIntervalSuggestion?.let { suggestion ->
        // #716: reads the suggestion's own live-recomputed currentIntervalEffective, not the stale
        // Plant.wateringIntervalDays literal (which only updates on a manual edit/apply/bootstrap and
        // drifts from the true seasonal value on its own) — matches Plant Detail's "currently" figure.
        val currentInterval = suggestion.currentIntervalEffective
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissSuggestedIntervalFromList(suggestion.plantId)
                pendingIntervalSuggestion = null
            },
            title = { Text(stringResource(R.string.interval_suggestion_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            R.string.interval_suggestion_body,
                            suggestion.suggestedIntervalEffective,
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
                            viewModel.applySuggestedIntervalFromList(
                                suggestion.plantId,
                                suggestion.suggestedInterval,
                                it,
                                suggestion.suggestedBaseInterval.takeIf { _ ->
                                    it == suggestion.suggestedIntervalEffective
                                }
                            )
                        }
                        pendingIntervalSuggestion = null
                    },
                    enabled = parsedInterval != null
                ) {
                    Text(stringResource(R.string.interval_suggestion_apply))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissSuggestedIntervalFromList(suggestion.plantId)
                        pendingIntervalSuggestion = null
                    }
                ) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        )
    }

    if (showBulkArchiveConfirm) {
        val count = selectedPlantIds.size
        AlertDialog(
            onDismissRequest = { showBulkArchiveConfirm = false },
            title = { Text(stringResource(R.string.bulk_delete_confirm_title)) },
            text = {
                Text(pluralStringResource(R.plurals.bulk_delete_confirm_text, count, count))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBulkArchiveConfirm = false
                        viewModel.bulkArchive()
                    }
                ) {
                    Text(stringResource(R.string.bulk_delete_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkArchiveConfirm = false }) {
                    Text(stringResource(R.string.bulk_delete_cancel_button))
                }
            }
        )
    }

    CameraPhotoDialogs(reminderCameraState)

    // Suppressed while an interval suggestion is showing so the two dialogs never stack; the
    // request stays in ViewModel state and surfaces once the interval dialog is dismissed.
    photoReminderRequest?.let { request ->
        if (pendingIntervalSuggestion == null) {
            PhotoReminderDialog(
                daysSince = request.daysSince.toInt(),
                onTakePhoto = {
                    reminderPlantId = request.plantId
                    viewModel.dismissPhotoReminder()
                    reminderCameraState.launch()
                },
                onDismiss = { viewModel.dismissPhotoReminder() }
            )
        }
    }
}

/**
 * The Sort icon + dropdown, shared verbatim (#512) between the normal top bar and the search top
 * bar's `actions` — "Tapping Sort opens the same dropdown and re-sorts the still-filtered (searched)
 * list; does not exit search mode."
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortMenuAction(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    sortOrder: SortOrder,
    onToggleSort: (SortOption) -> Unit
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.cd_sort_plants))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            val sortAlpha = stringResource(R.string.sort_alphabetical)
            val sortAlphaAsc = stringResource(R.string.sort_alphabetical_asc)
            val sortAlphaDesc = stringResource(R.string.sort_alphabetical_desc)
            val sortWatering = stringResource(R.string.sort_watering_due)
            val sortFertilizing = stringResource(R.string.sort_fertilizing_due)
            val sortRecent = stringResource(R.string.sort_recently_added)
            val sortBothDue = stringResource(R.string.sort_both_due)
            val sortCaredToday = stringResource(R.string.sort_cared_for_today)
            val sortActiveIssues = stringResource(R.string.sort_active_issues)
            SortOption.entries.forEach { option ->
                val isActive = sortOrder.option == option
                val label = when (option) {
                    SortOption.ALPHABETICAL -> if (isActive) {
                        if (sortOrder.direction == SortDirection.ASC) sortAlphaAsc else sortAlphaDesc
                    } else {
                        sortAlpha
                    }
                    SortOption.WATERING_DUE -> sortWatering
                    SortOption.FERTILIZING_DUE -> sortFertilizing
                    SortOption.RECENTLY_ADDED -> sortRecent
                    SortOption.BOTH_DUE -> sortBothDue
                    SortOption.CARED_FOR_TODAY -> sortCaredToday
                    SortOption.ACTIVE_ISSUES -> sortActiveIssues
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
                        onToggleSort(option)
                        onExpandedChange(false)
                    }
                )
            }
        }
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
        DateBucket.Dormant -> stringResource(R.string.date_group_dormant)
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
