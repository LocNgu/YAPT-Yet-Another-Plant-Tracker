package com.yapt.planttracker.ui.screens.plantdetail

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.insights.CareInsights
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.CustomReminderStatus
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
import com.yapt.planttracker.ui.theme.OverdueRed
import com.yapt.planttracker.ui.theme.WarnOrange
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

    var selectedTab by rememberSaveable { mutableStateOf(PlantDetailTab.WATER) }

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

    val showDialog = suggestedInterval != null &&
        plant != null &&
        suggestedInterval != plant?.wateringIntervalDays

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSuggestedInterval() },
            title = { Text(stringResource(R.string.interval_suggestion_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            R.string.interval_suggestion_body,
                            suggestedInterval!!,
                            plant!!.wateringIntervalDays ?: 0
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

                    // #434 quick-log chips stay above the tab strip as an always-visible summary.
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
                    }

                    if (!tabsEnabled) {
                        // Classic single-page layout (feature flag off): watering-due actions, chart, gallery.
                        careStatus?.let { status ->
                            if (plant?.wateringIntervalDays != null && (status.isOverdue || status.isDueSoon)) {
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
                    }

                    // Always-visible core section (#232), not gated behind PLANT_DETAIL_TABS.
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

                    // Always-visible core section (#564), not gated behind PLANT_DETAIL_TABS.
                    item {
                        PlantIssuesCard(
                            issues = activeIssues,
                            customReminderNameById = customReminderNameById,
                            onReport = { showReportIssueDialog = true },
                            onResolve = { issueToResolve = it }
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    // Tab strip inside the Box overlay's scrolling content (technical ADR-0018).
                    if (tabsEnabled) {
                        item {
                            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                                PlantDetailTab.entries.forEach { tab ->
                                    Tab(
                                        selected = selectedTab == tab,
                                        onClick = { selectedTab = tab },
                                        text = { Text(stringResource(tab.labelRes)) },
                                        icon = { Icon(tab.icon, contentDescription = null) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        when (selectedTab) {
                            PlantDetailTab.WATER -> {
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
                                        lastAtLabel = null
                                    )
                                    if (insights.isNotEmpty()) {
                                        TabInsightsCard(insights)
                                        Spacer(Modifier.height(16.dp))
                                    }
                                }
                                careStatus?.let { status ->
                                    if (plant?.wateringIntervalDays != null &&
                                        (status.isOverdue || status.isDueSoon)
                                    ) {
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
                                        lastAtLabel = null
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
 * adds a "last done" row (used by the Repot tab, which has no summary chip above the tabs); pass
 * `null` where the StatsRow above the tabs already shows the last event.
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
 * Bundles the [CustomRemindersCard]/[CustomReminderRow] callbacks into one parameter so neither
 * composable's parameter list trips Detekt's `LongParameterList` (mirrors [IntervalSetting] bundling
 * a config struct for the same reason).
 */
private data class CustomReminderActions(
    val onAdd: () -> Unit,
    val onEdit: (CustomReminder) -> Unit,
    val onDelete: (CustomReminder) -> Unit,
    val onMarkDone: (CustomReminder) -> Unit
)

/**
 * Always-visible "Custom reminders" section (#232) — unbounded, free-text recurring reminders per
 * plant, deliberately not gated behind [com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry.PLANT_DETAIL_TABS]
 * unlike the per-action tabs below it.
 */
@Composable
private fun CustomRemindersCard(
    reminders: List<CustomReminder>,
    statuses: List<CustomReminderStatus>,
    actions: CustomReminderActions,
    modifier: Modifier = Modifier
) {
    val statusById = remember(statuses) { statuses.associateBy { it.reminder.id } }
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
                    text = stringResource(R.string.custom_reminders_section),
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = actions.onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_custom_reminder))
                }
            }
            if (reminders.isEmpty()) {
                Text(
                    text = stringResource(R.string.custom_reminders_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            } else {
                reminders.forEach { reminder ->
                    CustomReminderRow(
                        reminder = reminder,
                        status = statusById[reminder.id],
                        actions = actions
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomReminderRow(
    reminder: CustomReminder,
    status: CustomReminderStatus?,
    actions: CustomReminderActions,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = reminder.name, style = MaterialTheme.typography.bodyLarge)
            val intervalText = pluralStringResource(
                R.plurals.custom_reminder_interval_summary,
                reminder.intervalDays,
                reminder.intervalDays
            )
            val countdown = status?.nextDueAt?.let { DateUtils.formatCountdown(it) }
            val statusColor = when {
                status?.isOverdue == true -> OverdueRed
                status?.isDueSoon == true -> WarnOrange
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = if (countdown != null) "$intervalText · $countdown" else intervalText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )
        }
        IconButton(onClick = { actions.onMarkDone(reminder) }) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.cd_mark_custom_reminder_done, reminder.name),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = { actions.onEdit(reminder) }) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.cd_edit_custom_reminder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { actions.onDelete(reminder) }) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.cd_delete_custom_reminder),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Add/edit dialog for a single [CustomReminder]. [initial] `null` means "add"; non-null pre-fills
 * the fields for editing. Plain-days interval only — no months toggle (unlike repotting), since
 * disease/treatment cadences are day-scale (issue #232 spec clarifications).
 */
@Composable
private fun CustomReminderDialog(
    initial: CustomReminder?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, intervalDays: Int) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var intervalText by remember(initial) { mutableStateOf((initial?.intervalDays ?: 7).toString()) }
    val parsedInterval = intervalText.toIntOrNull()?.takeIf { it >= 1 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial != null) R.string.custom_reminder_edit_title else R.string.custom_reminder_add_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.custom_reminder_name_label)) },
                    placeholder = { Text(stringResource(R.string.custom_reminder_name_placeholder)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { intervalText = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.custom_reminder_interval_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsedInterval?.let { onConfirm(name.trim(), it) } },
                enabled = name.isNotBlank() && parsedInterval != null
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
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
