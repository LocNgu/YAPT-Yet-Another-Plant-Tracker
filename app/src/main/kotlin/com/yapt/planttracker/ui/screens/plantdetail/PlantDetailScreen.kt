package com.yapt.planttracker.ui.screens.plantdetail

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.insights.CareInsights
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.model.RescheduleReason
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.ui.components.CameraPhotoDialogs
import com.yapt.planttracker.ui.components.CareLogItem
import com.yapt.planttracker.ui.components.EmptyStateView
import com.yapt.planttracker.ui.components.FullScreenPhotoViewer
import com.yapt.planttracker.ui.components.PhotoGallery
import com.yapt.planttracker.ui.components.PhotoReminderDialog
import com.yapt.planttracker.ui.components.RescheduleReasonBottomSheet
import com.yapt.planttracker.ui.components.SeasonalWateringCurveChart
import com.yapt.planttracker.ui.components.StatsRow
import com.yapt.planttracker.ui.components.WateringHistoryChart
import com.yapt.planttracker.ui.components.WateringReasonBottomSheet
import com.yapt.planttracker.ui.components.rememberCameraPhotoState
import com.yapt.planttracker.util.DateUtils
import kotlin.math.roundToInt

/** Test tag on the Plant Detail scrolling `LazyColumn`, so instrumented tests can scroll it to a
 *  specific node on the small (320x640) CI emulator without ambiguity with the chart's own scroll. */
internal const val PLANT_DETAIL_CONTENT_TEST_TAG = "plant_detail_content"

@Composable
fun PlantDetailScreen(
    viewModel: PlantDetailViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: () -> Unit,
    onNavigateToAddLog: () -> Unit,
    onNavigateToEditLog: (careLogId: Long) -> Unit
) {
    val plant by viewModel.plant.collectAsStateWithLifecycle()
    val careLogs by viewModel.careLogs.collectAsStateWithLifecycle()
    val customReminders by viewModel.customReminders.collectAsStateWithLifecycle()
    val customReminderStatuses by viewModel.customReminderStatuses.collectAsStateWithLifecycle()
    val activeIssues by viewModel.activeIssues.collectAsStateWithLifecycle()
    val galleryPhotos by viewModel.galleryPhotos.collectAsStateWithLifecycle()
    val careStatus by viewModel.careStatus.collectAsStateWithLifecycle()
    val suggestedInterval by viewModel.suggestedWateringInterval.collectAsStateWithLifecycle()
    val pendingWateringSuggestion by viewModel.pendingWateringSuggestion.collectAsStateWithLifecycle()
    val selectedTimeRange by viewModel.selectedTimeRange.collectAsStateWithLifecycle()
    val showRescheduleDialog by viewModel.showRescheduleDialog.collectAsStateWithLifecycle()
    val showRescheduleReasonSheet by viewModel.showRescheduleReasonSheet.collectAsStateWithLifecycle()
    val rescheduleReason by viewModel.rescheduleReason.collectAsStateWithLifecycle()
    val rescheduleSuggestedDays by viewModel.rescheduleSuggestedDays.collectAsStateWithLifecycle()
    val showPhotoReminderDialog by viewModel.showPhotoReminderDialog.collectAsStateWithLifecycle()
    val photoReminderDaysSince by viewModel.photoReminderDaysSince.collectAsStateWithLifecycle()
    val tabsEnabled by viewModel.tabsEnabled.collectAsStateWithLifecycle()
    val seasonalWateringEnabled by viewModel.seasonalWateringEnabled.collectAsStateWithLifecycle()
    val seasonalAmplitudeValue by viewModel.seasonalAmplitudeValue.collectAsStateWithLifecycle()
    val wateringExplanation by viewModel.wateringExplanation.collectAsStateWithLifecycle()
    var showWateringExplanationSheet by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    var showWaterSheet by remember { mutableStateOf(false) }
    var showLiquidFertilizeSheet by remember { mutableStateOf(false) }
    val reminderCameraState = rememberCameraPhotoState(snackbarHostState) { uri ->
        viewModel.saveReminderPhoto(uri)
        viewModel.dismissPhotoReminder()
    }

    val hasPhoto = plant?.coverPhotoUri != null
    val iconTint = if (hasPhoto) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    val iconContainerColor = if (hasPhoto) Color.Black.copy(alpha = 0.60f) else Color.Transparent

    // Signals something is hidden behind the collapsed tab row (#590) — reuses the same
    // already-collected activeIssues/customReminderStatuses the always-visible cards use.
    val tabRowHasAttention = activeIssues.isNotEmpty() || customReminderStatuses.any { it.isOverdue }

    var fullScreenPhotoIndex by remember { mutableStateOf<Int?>(null) }
    val galleryUris = remember(galleryPhotos) { galleryPhotos.map { it.uri } }
    var photoToDelete by remember { mutableStateOf<GalleryPhoto?>(null) }

    LaunchedEffect(galleryPhotos) {
        val idx = fullScreenPhotoIndex ?: return@LaunchedEffect
        if (galleryPhotos.isEmpty()) {
            fullScreenPhotoIndex = null
        } else if (idx >= galleryPhotos.size) {
            fullScreenPhotoIndex = galleryPhotos.lastIndex
        }
    }

    var isExpanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "chevronRotation"
    )

    // Edit fades out once the hero photo (the LazyColumn's item index 0) has fully scrolled past —
    // Back and the FAB stay pinned regardless of scroll (technical ADR-0022).
    val listState = rememberLazyListState()
    val scrolledPastHero = listState.firstVisibleItemIndex > 0

    var selectedTab by rememberSaveable { mutableStateOf(PlantDetailTab.WATER) }
    var isTabRowExpanded by rememberSaveable { mutableStateOf(false) }

    var intervalFieldText by remember(suggestedInterval) {
        mutableStateOf(suggestedInterval?.toString().orEmpty())
    }
    val parsedInterval = intervalFieldText.toIntOrNull()?.takeIf { it >= 1 }

    var showAddReminderDialog by remember { mutableStateOf(false) }
    var editingReminder by remember { mutableStateOf<CustomReminder?>(null) }
    var reminderToDelete by remember { mutableStateOf<CustomReminder?>(null) }
    val customReminderNameById = remember(customReminders) { customReminders.associate { it.id to it.name } }

    var showReportIssueDialog by remember { mutableStateOf(false) }
    var issueToResolve by remember { mutableStateOf<PlantIssue?>(null) }

    LaunchedEffect(suggestedInterval, plant?.wateringIntervalDays) {
        val s = suggestedInterval
        val current = plant?.wateringIntervalDays
        if (s != null && current != null && s == current) {
            viewModel.clearSuggestedInterval()
        }
    }

    val intervalAutoAppliedTemplate = stringResource(R.string.interval_auto_applied_snackbar)
    val undoLabel = stringResource(R.string.snackbar_undo)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PlantDetailViewModel.Event.SilentIntervalApplied -> {
                    val result = snackbarHostState.showSnackbar(
                        message = String.format(intervalAutoAppliedTemplate, event.afterIntervalDays),
                        actionLabel = undoLabel,
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.undoSilentIntervalApply(event.beforeIntervalDays)
                    }
                }
                else -> {}
            }
        }
    }

    // Resource templates resolved in composition (not via LocalContext, which lint forbids); the
    // plant name from the event is substituted when the message fires.
    val wateredTemplate = stringResource(R.string.quick_log_watered)
    val fertilizedTemplate = stringResource(R.string.quick_log_fertilized)
    val wateredAndFertilizedTemplate = stringResource(R.string.quick_log_watered_and_fertilized)
    val alreadyWateredTemplate = stringResource(R.string.quick_log_already_watered)
    val alreadyFertilizedTemplate = stringResource(R.string.quick_log_already_fertilized)
    val stillMoistCheckedTemplate = stringResource(R.string.quick_log_still_moist_checked)
    val alreadyCheckedTemplate = stringResource(R.string.quick_log_already_checked)
    LaunchedEffect(Unit) {
        viewModel.quickLogMessage.collect { message ->
            val text = when (message) {
                is PlantDetailViewModel.QuickLogMessage.Watered ->
                    String.format(wateredTemplate, message.plantName)
                is PlantDetailViewModel.QuickLogMessage.Fertilized ->
                    String.format(fertilizedTemplate, message.plantName)
                is PlantDetailViewModel.QuickLogMessage.WateredAndFertilized ->
                    String.format(wateredAndFertilizedTemplate, message.plantName)
                is PlantDetailViewModel.QuickLogMessage.AlreadyWateredToday ->
                    String.format(alreadyWateredTemplate, message.plantName)
                is PlantDetailViewModel.QuickLogMessage.AlreadyFertilizedToday ->
                    String.format(alreadyFertilizedTemplate, message.plantName)
                is PlantDetailViewModel.QuickLogMessage.StillMoistChecked ->
                    String.format(stillMoistCheckedTemplate, message.plantName)
                is PlantDetailViewModel.QuickLogMessage.AlreadyCheckedToday ->
                    String.format(alreadyCheckedTemplate, message.plantName)
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    photoToDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { photoToDelete = null },
            title = { Text(stringResource(R.string.delete_photo)) },
            text = { Text(stringResource(R.string.delete_photo_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePhoto(photo)
                    photoToDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { photoToDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    fullScreenPhotoIndex?.let { index ->
        FullScreenPhotoViewer(
            photos = galleryPhotos,
            initialIndex = index,
            onDismiss = { fullScreenPhotoIndex = null },
            onDelete = { uri ->
                photoToDelete = galleryPhotos.firstOrNull { it.uri == uri }
            }
        )
    }

    if (showRescheduleReasonSheet) {
        RescheduleReasonBottomSheet(
            onDismiss = { viewModel.dismissRescheduleReasonSheet() },
            onReasonChosen = { reason -> viewModel.chooseRescheduleReason(reason) }
        )
    }

    if (showRescheduleDialog) {
        RescheduleWateringDialog(
            // Pulling the date to today would contradict "soil still moist", so that option is
            // only ever offered for a deferral the user attributed to themselves (#586).
            todayEnabled = careStatus?.isOverdue == true && rescheduleReason != RescheduleReason.SOIL_STILL_MOIST,
            onDismiss = { viewModel.dismissRescheduleDialog() },
            onToday = { viewModel.confirmRescheduleToday() },
            onRelativeDays = { days -> viewModel.confirmRescheduleRelativeDays(days) },
            onCustomDate = { dateMillis -> viewModel.confirmRescheduleCustomDate(dateMillis) },
            suggestedDays = rescheduleSuggestedDays
        )
    }

    if (showWateringExplanationSheet) {
        wateringExplanation?.let { explanation ->
            WateringExplanationSheet(
                explanation = explanation,
                onDismiss = { showWateringExplanationSheet = false }
            )
        }
    }

    if (showAddReminderDialog || editingReminder != null) {
        CustomReminderDialog(
            initial = editingReminder,
            onDismiss = {
                showAddReminderDialog = false
                editingReminder = null
            },
            onConfirm = { name, intervalDays ->
                val target = editingReminder
                if (target != null) {
                    viewModel.updateCustomReminder(target, name, intervalDays)
                } else {
                    viewModel.addCustomReminder(name, intervalDays)
                }
                showAddReminderDialog = false
                editingReminder = null
            }
        )
    }

    reminderToDelete?.let { reminder ->
        AlertDialog(
            onDismissRequest = { reminderToDelete = null },
            title = { Text(stringResource(R.string.custom_reminder_delete_title)) },
            text = { Text(stringResource(R.string.custom_reminder_delete_confirm, reminder.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCustomReminder(reminder)
                    reminderToDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { reminderToDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showReportIssueDialog) {
        ReportIssueDialog(
            onDismiss = { showReportIssueDialog = false },
            onConfirm = { name, reminderName, reminderIntervalDays ->
                viewModel.reportIssue(name, reminderName, reminderIntervalDays)
                showReportIssueDialog = false
            }
        )
    }

    issueToResolve?.let { issue ->
        ResolveIssueDialog(
            issue = issue,
            onDismiss = { issueToResolve = null },
            onConfirm = { note ->
                viewModel.resolveIssue(issue, note)
                issueToResolve = null
            }
        )
    }

    // pendingWateringSuggestion bundles raw+converted+current into one atomically-updating tuple
    // (#620 round 2) so the dialog can never render a stale/unconverted number for a frame, and is
    // null outright whenever the entire "jump" is a base/effective unit-mismatch artifact (#620).
    val suggestion = pendingWateringSuggestion
    val showDialog = suggestion != null

    if (showDialog && suggestion != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSuggestedInterval() },
            title = { Text(stringResource(R.string.interval_suggestion_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            R.string.interval_suggestion_body,
                            suggestion.effectiveIntervalDays,
                            suggestion.currentIntervalDays ?: 0
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
                    onClick = { parsedInterval?.let { viewModel.applySuggestedInterval(it) } },
                    enabled = parsedInterval != null
                ) {
                    Text(stringResource(R.string.interval_suggestion_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSuggestedInterval() }) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        )
    }

    CameraPhotoDialogs(reminderCameraState)

    // Suppressed while the interval-suggestion dialog is showing so the two never stack
    // (matches PlantListScreen).
    if (showPhotoReminderDialog && !showDialog) {
        PhotoReminderDialog(
            daysSince = photoReminderDaysSince.toInt(),
            onTakePhoto = {
                viewModel.dismissPhotoReminder()
                reminderCameraState.launch()
            },
            onDismiss = { viewModel.dismissPhotoReminder() }
        )
    }

    if (showWaterSheet) {
        plant?.let { p ->
            WateringReasonBottomSheet(
                plantName = p.name,
                gapRanLong = careStatus?.isWateringGapLong == true,
                onDismiss = { showWaterSheet = false },
                onLog = { reason ->
                    viewModel.quickWater(reason)
                    showWaterSheet = false
                }
            )
        }
    }

    if (showLiquidFertilizeSheet) {
        plant?.let { p ->
            WateringReasonBottomSheet(
                plantName = p.name,
                gapRanLong = careStatus?.isWateringGapLong == true,
                title = stringResource(R.string.water_fertilize_feedback_sheet_title, p.name),
                onDismiss = { showLiquidFertilizeSheet = false },
                onLog = { reason ->
                    viewModel.quickLiquidFertilize(reason)
                    showLiquidFertilizeSheet = false
                }
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(Modifier.fillMaxSize()) {
            if (plant != null) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(PLANT_DETAIL_CONTENT_TEST_TAG),
                    contentPadding = PaddingValues(
                        bottom = 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    ),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                        ) {
                            if (plant?.coverPhotoUri != null) {
                                AsyncImage(
                                    model = plant!!.coverPhotoUri,
                                    contentDescription = stringResource(R.string.cd_plant_cover_photo),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable { fullScreenPhotoIndex = galleryPhotos.indexOfFirst { it.uri == plant!!.coverPhotoUri }.takeIf { it >= 0 } }
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(80.dp)
                                        .align(Alignment.TopStart)
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color.Black.copy(alpha = 0.4f),
                                                    Color.Transparent
                                                )
                                            )
                                        )
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.LocalFlorist,
                                        contentDescription = null,
                                        modifier = Modifier.size(80.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = plant!!.name,
                                style = MaterialTheme.typography.headlineMedium
                            )
                            plant?.species?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            plant?.room?.let {
                                Text(
                                    text = stringResource(R.string.plant_detail_location, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            plant?.wateringIntervalDays?.let {
                                Text(
                                    text = stringResource(R.string.plant_detail_watering_interval, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (!tabsEnabled) {
                        // Classic single-page layout (feature flag off): #434 quick-log chips,
                        // watering-due actions, chart, gallery. StatsRow stays above the (absent)
                        // tab strip as an always-visible summary here (tabs layout drops it, #603).
                        careStatus?.let { status ->
                            item {
                                StatsRow(
                                    status = status,
                                    onWaterClick = { requestWater(status, viewModel) { showWaterSheet = true } },
                                    onFertilizeClick = {
                                        if (plant?.useLiquidFertilizer == true) {
                                            requestLiquidFertilize(status, viewModel) {
                                                showLiquidFertilizeSheet = true
                                            }
                                        } else {
                                            viewModel.quickFertilize()
                                        }
                                    }
                                )
                                Spacer(Modifier.height(16.dp))
                            }
                            if (plant?.wateringIntervalDays != null) {
                                item {
                                    WateringDueActionsRow(
                                        onWaterClick = { requestWater(status, viewModel) { showWaterSheet = true } },
                                        onRescheduleClick = { viewModel.requestReschedule() }
                                    )
                                    Spacer(Modifier.height(16.dp))
                                }
                            }
                        }
                        item {
                            WateringHistoryChart(
                                careLogs = careLogs,
                                selectedRange = selectedTimeRange,
                                onRangeSelected = { viewModel.setTimeRange(it) }
                            )
                        }
                        if (galleryPhotos.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.plant_detail_photos_section),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                PhotoGallery(
                                    photoUris = galleryUris,
                                    onPhotoClick = { uri ->
                                        fullScreenPhotoIndex =
                                            galleryPhotos.indexOfFirst { it.uri == uri }.takeIf { it >= 0 }
                                    }
                                )
                                Spacer(Modifier.height(16.dp))
                            }
                        }

                        // Always-visible in the classic layout only (#232/#564, product ADR-0030);
                        // in the tabs layout these move into the CUSTOM_REMINDERS/ISSUES tabs below.
                        item {
                            CustomRemindersCard(
                                reminders = customReminders,
                                statuses = customReminderStatuses,
                                actions = CustomReminderActions(
                                    onAdd = { showAddReminderDialog = true },
                                    onEdit = { editingReminder = it },
                                    onDelete = { reminderToDelete = it },
                                    onMarkDone = { viewModel.markCustomReminderDone(it) }
                                )
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                        item {
                            PlantIssuesCard(
                                issues = activeIssues,
                                customReminderNameById = customReminderNameById,
                                onReport = { showReportIssueDialog = true },
                                onResolve = { issueToResolve = it }
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    // Tab strip inside the Box overlay's scrolling content (technical ADR-0018).
                    // Collapse/expand + attention badge: product ADR-0030 (#590).
                    if (tabsEnabled) {
                        item {
                            PlantDetailTabStrip(
                                state = TabStripState(
                                    selectedTab = selectedTab,
                                    isExpanded = isTabRowExpanded,
                                    hasAttention = tabRowHasAttention
                                ),
                                onTabSelected = { selectedTab = it },
                                onToggleExpanded = {
                                    val expanding = !isTabRowExpanded
                                    isTabRowExpanded = expanding
                                    val onHiddenTab = selectedTab == PlantDetailTab.CUSTOM_REMINDERS ||
                                        selectedTab == PlantDetailTab.ISSUES
                                    if (!expanding && onHiddenTab) {
                                        selectedTab = PlantDetailTab.WATER
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        when (selectedTab) {
                            PlantDetailTab.WATER -> {
                                careStatus?.let { status ->
                                    if (plant?.wateringIntervalDays != null) {
                                        item {
                                            WateringDueActionsRow(
                                                onWaterClick = {
                                                    requestWater(status, viewModel) { showWaterSheet = true }
                                                },
                                                onRescheduleClick = { viewModel.requestReschedule() }
                                            )
                                            Spacer(Modifier.height(16.dp))
                                        }
                                    }
                                }
                                item {
                                    InlineIntervalSetting(
                                        setting = IntervalSetting(
                                            enabled = plant?.wateringIntervalDays != null,
                                            days = plant?.wateringIntervalDays
                                                ?: PlantDetailViewModel.DEFAULT_WATERING_INTERVAL_DAYS,
                                            range = 1..60,
                                            enabledLabelRes = R.string.watering_interval_label,
                                            disabledLabelRes = R.string.watering_reminder_label
                                        ),
                                        onIntervalChange = { viewModel.setWateringInterval(it) }
                                    ) {
                                        if (seasonalWateringEnabled && plant?.wateringIntervalDays != null) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.pin_interval_label),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Switch(
                                                    modifier = Modifier.testTag("pin_interval_switch"),
                                                    checked = plant?.pinIntervalToBase == true,
                                                    onCheckedChange = { viewModel.setPinIntervalToBase(it) }
                                                )
                                            }
                                            val hemisphere = remember { SeasonalWatering.currentHemisphere() }
                                            SeasonalWateringCurveChart(
                                                amplitude = seasonalAmplitudeValue,
                                                hemisphere = hemisphere,
                                                isPinned = plant?.pinIntervalToBase == true,
                                                modifier = Modifier.padding(top = 12.dp)
                                            )
                                        }
                                        if (plant?.wateringIntervalDays != null) {
                                            TextButton(
                                                onClick = { showWateringExplanationSheet = true },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("why_this_date_button")
                                            ) {
                                                Text(stringResource(R.string.why_this_date_button))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(16.dp))
                                }
                                item {
                                    val insights = careTypeInsightItems(
                                        summary = CareInsights.summarize(careLogs, CareType.WATER),
                                        countLabel = stringResource(R.string.insight_waterings),
                                        lastAtLabel = stringResource(R.string.insight_last_watered)
                                    )
                                    if (insights.isNotEmpty()) {
                                        TabInsightsCard(insights)
                                        Spacer(Modifier.height(16.dp))
                                    }
                                }
                                item {
                                    WateringHistoryChart(
                                        careLogs = careLogs,
                                        selectedRange = selectedTimeRange,
                                        onRangeSelected = { viewModel.setTimeRange(it) }
                                    )
                                }
                                // Misting is folded into the Water tab (#436): a recent-mists list.
                                val mistLogs = careLogs.filter { it.careType == CareType.MIST }
                                if (mistLogs.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = stringResource(R.string.plant_detail_misting_section),
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                    }
                                    items(mistLogs, key = { "mist-${it.id}" }) { log ->
                                        CareLogItem(
                                            log = log,
                                            onEdit = { onNavigateToEditLog(log.id) },
                                            onDelete = { viewModel.deleteLog(log) },
                                            customReminderName = log.customReminderId?.let { customReminderNameById[it] }
                                        )
                                    }
                                }
                            }

                            PlantDetailTab.FERTILIZE -> {
                                careStatus?.let { status ->
                                    if (plant?.fertilizingIntervalDays != null) {
                                        item {
                                            FertilizeDueActionRow(
                                                onFertilizeClick = {
                                                    if (plant?.useLiquidFertilizer == true) {
                                                        requestLiquidFertilize(status, viewModel) {
                                                            showLiquidFertilizeSheet = true
                                                        }
                                                    } else {
                                                        viewModel.quickFertilize()
                                                    }
                                                }
                                            )
                                            Spacer(Modifier.height(16.dp))
                                        }
                                    }
                                }
                                item {
                                    InlineIntervalSetting(
                                        setting = IntervalSetting(
                                            enabled = plant?.fertilizingIntervalDays != null,
                                            days = plant?.fertilizingIntervalDays
                                                ?: PlantDetailViewModel.DEFAULT_FERTILIZING_INTERVAL_DAYS,
                                            range = 1..180,
                                            enabledLabelRes = R.string.fertilizing_interval_label,
                                            disabledLabelRes = R.string.fertilizing_reminder_label
                                        ),
                                        onIntervalChange = { viewModel.setFertilizingInterval(it) }
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.liquid_fertilizer_label),
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Switch(
                                                checked = plant?.useLiquidFertilizer == true,
                                                onCheckedChange = { viewModel.setLiquidFertilizer(it) }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(16.dp))
                                }
                                item {
                                    val insights = careTypeInsightItems(
                                        summary = CareInsights.summarize(careLogs, CareType.FERTILIZE),
                                        countLabel = stringResource(R.string.insight_fertilizings),
                                        lastAtLabel = stringResource(R.string.insight_last_fertilized)
                                    )
                                    if (insights.isNotEmpty()) {
                                        TabInsightsCard(insights)
                                        Spacer(Modifier.height(16.dp))
                                    }
                                }
                                val fertLogs = careLogs.filter { it.careType == CareType.FERTILIZE }
                                if (fertLogs.isEmpty()) {
                                    item {
                                        Box(modifier = Modifier.height(160.dp)) {
                                            EmptyStateView(
                                                message = stringResource(R.string.plant_detail_tab_fertilize_empty),
                                                icon = Icons.Filled.Spa
                                            )
                                        }
                                    }
                                } else {
                                    items(fertLogs, key = { "fert-${it.id}" }) { log ->
                                        CareLogItem(
                                            log = log,
                                            onEdit = { onNavigateToEditLog(log.id) },
                                            onDelete = { viewModel.deleteLog(log) },
                                            customReminderName = log.customReminderId?.let { customReminderNameById[it] }
                                        )
                                    }
                                }
                            }

                            PlantDetailTab.REPOT -> {
                                item {
                                    val insights = careTypeInsightItems(
                                        summary = CareInsights.summarize(careLogs, CareType.REPOT),
                                        countLabel = stringResource(R.string.insight_repottings),
                                        lastAtLabel = stringResource(R.string.insight_last_repotted)
                                    )
                                    if (insights.isNotEmpty()) {
                                        TabInsightsCard(insights)
                                        Spacer(Modifier.height(16.dp))
                                    }
                                }
                                val repotLogs = careLogs.filter { it.careType == CareType.REPOT }
                                if (repotLogs.isEmpty()) {
                                    item {
                                        Box(modifier = Modifier.height(160.dp)) {
                                            EmptyStateView(
                                                message = stringResource(R.string.plant_detail_tab_repot_empty),
                                                icon = Icons.Filled.LocalFlorist
                                            )
                                        }
                                    }
                                } else {
                                    items(repotLogs, key = { "repot-${it.id}" }) { log ->
                                        CareLogItem(
                                            log = log,
                                            onEdit = { onNavigateToEditLog(log.id) },
                                            onDelete = { viewModel.deleteLog(log) },
                                            customReminderName = log.customReminderId?.let { customReminderNameById[it] }
                                        )
                                    }
                                }
                            }

                            PlantDetailTab.PHOTO -> {
                                item {
                                    val summary = CareInsights.summarizePhotos(galleryPhotos)
                                    if (summary.count > 0) {
                                        val items = mutableListOf(
                                            stringResource(R.string.insight_photos) to summary.count.toString()
                                        )
                                        val first = summary.firstAt
                                        val last = summary.lastAt
                                        if (first != null && last != null && first != last) {
                                            items += stringResource(R.string.insight_first_photo) to DateUtils.formatDate(first)
                                            items += stringResource(R.string.insight_latest_photo) to DateUtils.formatDate(last)
                                        }
                                        TabInsightsCard(items)
                                        Spacer(Modifier.height(16.dp))
                                    }
                                }
                                if (galleryPhotos.isEmpty()) {
                                    item {
                                        Box(modifier = Modifier.height(160.dp)) {
                                            EmptyStateView(
                                                message = stringResource(R.string.plant_detail_tab_photo_empty),
                                                icon = Icons.Filled.PhotoLibrary
                                            )
                                        }
                                    }
                                } else {
                                    item {
                                        PhotoGallery(
                                            photoUris = galleryUris,
                                            onPhotoClick = { uri ->
                                                fullScreenPhotoIndex =
                                                    galleryPhotos.indexOfFirst { it.uri == uri }.takeIf { it >= 0 }
                                            }
                                        )
                                        Spacer(Modifier.height(16.dp))
                                    }
                                }
                            }

                            PlantDetailTab.CUSTOM_REMINDERS -> {
                                item {
                                    CustomRemindersCard(
                                        reminders = customReminders,
                                        statuses = customReminderStatuses,
                                        actions = CustomReminderActions(
                                            onAdd = { showAddReminderDialog = true },
                                            onEdit = { editingReminder = it },
                                            onDelete = { reminderToDelete = it },
                                            onMarkDone = { viewModel.markCustomReminderDone(it) }
                                        )
                                    )
                                    Spacer(Modifier.height(16.dp))
                                }
                            }

                            PlantDetailTab.ISSUES -> {
                                item {
                                    PlantIssuesCard(
                                        issues = activeIssues,
                                        customReminderNameById = customReminderNameById,
                                        onReport = { showReportIssueDialog = true },
                                        onResolve = { issueToResolve = it }
                                    )
                                    Spacer(Modifier.height(16.dp))
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.care_history),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.plant_detail_care_logs_count, careLogs.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (careLogs.isEmpty()) {
                        item {
                            Box(modifier = Modifier.height(200.dp)) {
                                EmptyStateView(
                                    message = stringResource(R.string.no_care_logs_detail),
                                    icon = Icons.AutoMirrored.Filled.Notes
                                )
                            }
                        }
                    } else {
                        val visibleLogs = if (isExpanded) careLogs else careLogs.take(5)
                        items(visibleLogs, key = { it.id }) { log ->
                            CareLogItem(
                                log = log,
                                onEdit = { onNavigateToEditLog(log.id) },
                                onDelete = { viewModel.deleteLog(log) },
                                customReminderName = log.customReminderId?.let { customReminderNameById[it] }
                            )
                        }

                        if (careLogs.size > 5) {
                            item {
                                val remaining = careLogs.size - 5
                                AssistChip(
                                    onClick = { isExpanded = !isExpanded },
                                    label = {
                                        Text(
                                            if (isExpanded) {
                                                stringResource(R.string.care_history_show_less)
                                            } else {
                                                pluralStringResource(
                                                    R.plurals.care_history_show_more,
                                                    remaining,
                                                    remaining
                                                )
                                            }
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.ExpandMore,
                                            contentDescription = if (isExpanded) {
                                                stringResource(R.string.care_history_collapse_cd)
                                            } else {
                                                stringResource(R.string.care_history_expand_cd)
                                            },
                                            modifier = Modifier.rotate(chevronRotation)
                                        )
                                    },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .align(Alignment.TopStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = iconContainerColor
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = iconTint
                    )
                }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    visible = !scrolledPastHero,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    IconButton(
                        onClick = onNavigateToEdit,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = iconContainerColor
                        )
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.cd_edit_plant),
                            tint = iconTint
                        )
                    }
                }
            }

            FloatingActionButton(
                onClick = onNavigateToAddLog,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_log_care))
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 88.dp)
            )
        }
    }
}

/** How many tabs stay visible in [PlantDetailTabStrip]'s collapsed (default) state — today's four. */
private const val COLLAPSED_TAB_COUNT = 4

/**
 * Bundles [PlantDetailTabStrip]'s value parameters into one so the composable stays under Detekt's
 * `LongParameterList` threshold, mirroring [IntervalSetting]/[CustomReminderActions]'s bundling.
 */
private data class TabStripState(
    val selectedTab: PlantDetailTab,
    val isExpanded: Boolean,
    val hasAttention: Boolean
)

/** Corner radius of [PlantDetailTabStrip]'s selected-tab highlight, matching the app's other rounded cards/chips. */
private val TAB_SELECTION_INDICATOR_SHAPE = RoundedCornerShape(12.dp)

/**
 * The Plant Detail per-action tab strip (technical ADR-0018) plus its collapse/expand toggle
 * (product ADR-0030, #590). Collapsed (default) shows only the first [COLLAPSED_TAB_COUNT] entries
 * of [PlantDetailTab] — today's Water/Fertilize/Repot/Photo, unchanged in width or appearance;
 * expanded reveals all entries. Each [Tab] is `Modifier.fillMaxWidth(0.25f)` inside a [FlowRow] (not
 * a [androidx.compose.material3.TabRow]/`PrimaryTabRow`) so a tab's width is always a quarter of the
 * strip's full width regardless of how many tabs are currently visible — 4 fill exactly one row
 * (identical to today), and expanding to 6 wraps the extra 2 onto a second row at that same width,
 * rather than shrinking every tab or scrolling horizontally.
 *
 * `TabRow`/`PrimaryTabRow` draws the selected-tab indicator itself, as a separate overlay positioned
 * via real `TabPosition`s it measures from its own tab slots — unavailable here since these `Tab`s are
 * standalone children of a [FlowRow], not of a `TabRow`. Each `Tab` is therefore given its own explicit
 * `selectedContentColor`/`unselectedContentColor` (the two default to the same value when neither is
 * passed, which left selected and unselected tabs pixel-identical, #591) plus a rounded background
 * behind the selected tab's icon/text standing in for `PrimaryIndicator` — scoped to that one `Tab`'s
 * own `Modifier` so it works per-tab regardless of which row it wraps onto.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlantDetailTabStrip(
    state: TabStripState,
    onTabSelected: (PlantDetailTab) -> Unit,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleTabs = if (state.isExpanded) {
        PlantDetailTab.entries
    } else {
        PlantDetailTab.entries.take(COLLAPSED_TAB_COUNT)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            visibleTabs.forEach { tab ->
                val isSelected = state.selectedTab == tab
                Tab(
                    selected = isSelected,
                    onClick = { onTabSelected(tab) },
                    text = { Text(stringResource(tab.labelRes)) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth(0.25f)
                        .padding(4.dp)
                        .background(
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            shape = TAB_SELECTION_INDICATOR_SHAPE
                        )
                )
            }
        }
        TabRowExpandToggle(
            isExpanded = state.isExpanded,
            hasAttention = state.hasAttention,
            onToggle = onToggleExpanded,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Chevron control toggling [PlantDetailTabStrip] between collapsed/expanded — reuses the exact
 * chevron-rotate pattern the care-history `AssistChip` already uses in this file (#253) rather than
 * new iconography. Shows an attention [Badge] only while collapsed **and** [hasAttention] — once
 * expanded everything is already visible, so there is nothing left to flag. The [Badge] itself is a
 * bare dot with no semantics of its own, so a screen-reader user relies entirely on the toggle's own
 * announced description — folding an "attention needed" clause into that description (rather than the
 * Badge) while collapsed-and-attention is the only case where the badge is showing (#591).
 *
 * The tappable/clickable target is the full-width [Row], not just the icon — a plain `Modifier
 * .clickable` (not [IconButton], which caps its own touch target) so the whole strip beneath the tabs
 * expands/collapses on tap, not only the small chevron glyph (#591). `clickable`'s semantics node
 * merges its descendants, so the [Icon]'s `contentDescription` is what gets announced for the row;
 * `onClickLabel` is deliberately omitted since it would duplicate that same description in TalkBack's
 * announcement. `minimumInteractiveComponentSize()` restores the 48dp-minimum touch target [IconButton]
 * used to guarantee on its own, which the horizontal-only widening above doesn't otherwise cover (#597).
 */
@Composable
private fun TabRowExpandToggle(
    isExpanded: Boolean,
    hasAttention: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "tabRowChevronRotation"
    )
    val toggleCd = when {
        isExpanded -> stringResource(R.string.plant_detail_tabs_collapse_cd)
        hasAttention -> stringResource(R.string.plant_detail_tabs_expand_attention_cd)
        else -> stringResource(R.string.plant_detail_tabs_expand_cd)
    }
    Row(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onToggle)
            .testTag("plant_detail_tabs_toggle")
            .minimumInteractiveComponentSize()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BadgedBox(
            badge = {
                if (!isExpanded && hasAttention) {
                    Badge()
                }
            }
        ) {
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = toggleCd,
                modifier = Modifier.rotate(chevronRotation)
            )
        }
    }
}

/**
 * Config for an [InlineIntervalSetting]: whether the schedule is on, the day count to show, the
 * allowed range, and the label resources for the on/off header. Bundled into one value so the
 * composable stays within the parameter budget.
 */
private data class IntervalSetting(
    val enabled: Boolean,
    val days: Int,
    val range: IntRange,
    @StringRes val enabledLabelRes: Int,
    @StringRes val disabledLabelRes: Int
)

/**
 * Inline scheduling control shown at the top of the Water and Fertilize tabs (#436, product
 * ADR-0022): an enable [Switch] plus a [Slider]. It owns the slider's local position and reports
 * changes through [onIntervalChange] — the day count when enabled/committed, or `null` when the
 * schedule is switched off. The drag persists on release (`onValueChangeFinished`), not per frame.
 * [extra] renders additional rows inside the card when enabled (the liquid-fertilizer toggle on the
 * Fertilize tab).
 */
@Composable
private fun InlineIntervalSetting(
    setting: IntervalSetting,
    onIntervalChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    var sliderDays by remember(setting.days) { mutableIntStateOf(setting.days) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (setting.enabled) {
                        stringResource(setting.enabledLabelRes, sliderDays)
                    } else {
                        stringResource(setting.disabledLabelRes)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = setting.enabled,
                    onCheckedChange = { on -> onIntervalChange(if (on) sliderDays else null) }
                )
            }
            if (setting.enabled) {
                Slider(
                    value = sliderDays.toFloat(),
                    onValueChange = { sliderDays = it.roundToInt() },
                    onValueChangeFinished = { onIntervalChange(sliderDays) },
                    valueRange = setting.range.first.toFloat()..setting.range.last.toFloat(),
                    steps = setting.range.last - setting.range.first - 1
                )
                extra()
            }
        }
    }
}

/**
 * Builds the label/value rows for a care type's insight card (#436, sub-task 3). Returns an empty
 * list when there are no events of that type so the caller can skip the card entirely. [lastAtLabel]
 * adds a "last done" row; pass `null` only where another surface already shows the last event
 * (none currently do — `StatsRow` was removed from the tabs layout entirely, #603).
 */
@Composable
private fun careTypeInsightItems(
    summary: CareInsights.CareTypeSummary,
    countLabel: String,
    lastAtLabel: String?
): List<Pair<String, String>> {
    if (summary.count == 0) return emptyList()
    val items = mutableListOf(countLabel to summary.count.toString())
    val lastAt = summary.lastAt
    if (lastAtLabel != null && lastAt != null) {
        items += lastAtLabel to DateUtils.formatRelative(lastAt)
    }
    val average = summary.averageIntervalDays
    if (average != null) {
        items += stringResource(R.string.insight_avg_interval) to
            pluralStringResource(R.plurals.insight_interval_days, average, average)
    }
    return items
}

/** Presentational card listing label -> value insight rows for a Plant Detail tab (#436). */
@Composable
private fun TabInsightsCard(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            items.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(text = value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * The #586 fast path (product ADR-0030): an on-schedule watering is logged straight away — no sheet,
 * no question — and only an off-schedule one opens [WateringReasonBottomSheet] via [showReasonSheet].
 * Shared by the watering `StatChip` and the watering-due actions row so the two surfaces can never
 * disagree about when the question is worth asking.
 */
private fun requestWater(
    status: PlantCareStatus,
    viewModel: PlantDetailViewModel,
    showReasonSheet: () -> Unit
) {
    if (status.isWateringOnSchedule) viewModel.quickWater(reason = null) else showReasonSheet()
}

/** [requestWater]'s counterpart for a liquid-fertilizer plant, whose paired WATER log follows the same rule. */
private fun requestLiquidFertilize(
    status: PlantCareStatus,
    viewModel: PlantDetailViewModel,
    showReasonSheet: () -> Unit
) {
    if (status.isWateringOnSchedule) viewModel.quickLiquidFertilize(reason = null) else showReasonSheet()
}
