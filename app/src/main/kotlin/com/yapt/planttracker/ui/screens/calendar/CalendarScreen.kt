package com.yapt.planttracker.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import com.kizitonwose.calendar.core.nextMonth
import com.kizitonwose.calendar.core.previousMonth
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.ui.components.CameraPhotoDialogs
import com.yapt.planttracker.ui.components.EmptyStateView
import com.yapt.planttracker.ui.components.PhotoReminderDialog
import com.yapt.planttracker.ui.components.PlantPhoto
import com.yapt.planttracker.ui.components.WaterFeedbackBottomSheet
import com.yapt.planttracker.ui.components.rememberCameraPhotoState
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.ui.theme.OverdueRed
import com.yapt.planttracker.ui.theme.SageGreen
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onNavigateToPlant: (Long) -> Unit
) {
    val plantsByDay by viewModel.plantsByDay.collectAsStateWithLifecycle()
    val plantsWithStatus by viewModel.plantsWithStatus.collectAsStateWithLifecycle()
    val selectedDay by viewModel.selectedDay.collectAsStateWithLifecycle()
    val selectedDayPlants by viewModel.selectedDayPlants.collectAsStateWithLifecycle()
    val photoReminderRequest by viewModel.photoReminderRequest.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var waterFeedbackPlant by remember { mutableStateOf<PlantCareStatus?>(null) }
    var liquidFertilizeFeedbackPlant by remember { mutableStateOf<PlantCareStatus?>(null) }
    var pendingIntervalSuggestion by remember { mutableStateOf<QuickWaterSuggestion?>(null) }
    var intervalFieldText by remember(pendingIntervalSuggestion) {
        mutableStateOf(pendingIntervalSuggestion?.suggestedInterval?.toString().orEmpty())
    }
    val parsedInterval = intervalFieldText.toIntOrNull()?.takeIf { it > 0 }
    var reminderPlantId by rememberSaveable { mutableStateOf<Long?>(null) }
    val reminderCameraState = rememberCameraPhotoState(snackbarHostState) { uri ->
        reminderPlantId?.let { viewModel.saveReminderPhoto(it, uri) }
        reminderPlantId = null
        viewModel.dismissPhotoReminder()
    }

    LaunchedEffect(Unit) {
        viewModel.quickLogEvent.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    LaunchedEffect(Unit) {
        viewModel.quickWaterSuggestion.collect { suggestion -> pendingIntervalSuggestion = suggestion }
    }

    val today = remember { LocalDate.now() }
    val currentMonth = remember { YearMonth.now() }
    val calendarState = rememberCalendarState(
        startMonth = currentMonth.minusMonths(1200),
        endMonth = currentMonth.plusMonths(1200),
        firstVisibleMonth = currentMonth,
        firstDayOfWeek = firstDayOfWeekFromLocale()
    )
    val visibleYearMonth = calendarState.firstVisibleMonth.yearMonth
    LaunchedEffect(visibleYearMonth) {
        viewModel.setVisibleMonth(visibleYearMonth)
    }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.nav_tab_calendar)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            CalendarMonthHeader(
                visibleYearMonth = visibleYearMonth,
                onPrevious = {
                    coroutineScope.launch { calendarState.animateScrollToMonth(visibleYearMonth.previousMonth) }
                },
                onNext = {
                    coroutineScope.launch { calendarState.animateScrollToMonth(visibleYearMonth.nextMonth) }
                }
            )
            CalendarWeekHeader(firstDayOfWeek = calendarState.firstDayOfWeek)
            HorizontalCalendar(
                state = calendarState,
                dayContent = { day ->
                    CalendarDayCell(
                        day = day,
                        entry = if (day.position == DayPosition.MonthDate) plantsByDay[day.date] else null,
                        isToday = day.date == today,
                        onClick = {
                            if (day.position == DayPosition.MonthDate) {
                                viewModel.selectDay(day.date)
                            }
                        }
                    )
                }
            )
            if (plantsByDay.isEmpty()) {
                EmptyStateView(
                    message = stringResource(R.string.calendar_empty_state),
                    icon = Icons.Filled.CalendarMonth,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    val currentSelectedDay = selectedDay
    if (currentSelectedDay != null) {
        CalendarDaySheet(
            day = currentSelectedDay,
            today = today,
            plants = selectedDayPlants,
            onDismiss = { viewModel.selectDay(null) },
            onNavigateToPlant = { plantId ->
                viewModel.selectDay(null)
                onNavigateToPlant(plantId)
            },
            onQuickWater = { status -> waterFeedbackPlant = status },
            onQuickFertilize = { status ->
                if (status.plant.useLiquidFertilizer) {
                    liquidFertilizeFeedbackPlant = status
                } else {
                    viewModel.quickLog(status.plant.id, CareType.FERTILIZE)
                }
            }
        )
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
                            viewModel.applySuggestedInterval(suggestion.plantId, it)
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

    CameraPhotoDialogs(reminderCameraState)

    // Suppressed while an interval suggestion is showing so the two dialogs never stack,
    // mirroring PlantListScreen (#407).
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

@Composable
private fun CalendarMonthHeader(
    visibleYearMonth: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val locale = LocalConfiguration.current.locales[0]
        IconButton(onClick = onPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.calendar_prev_month_cd)
            )
        }
        Text(
            text = visibleYearMonth.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.calendar_next_month_cd)
            )
        }
    }
}

@Composable
private fun CalendarWeekHeader(firstDayOfWeek: DayOfWeek) {
    val locale = LocalConfiguration.current.locales[0]
    Row(modifier = Modifier.fillMaxWidth()) {
        daysOfWeek(firstDayOfWeek).forEach { dow ->
            Text(
                text = dow.getDisplayName(TextStyle.SHORT, locale),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CalendarDayCell(
    day: CalendarDay,
    entry: DayEntry?,
    isToday: Boolean,
    onClick: () -> Unit
) {
    val inMonth = day.position == DayPosition.MonthDate
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .then(
                if (inMonth) Modifier.clickable(onClick = onClick) else Modifier
            )
            .testTag("calendar_day_${day.date}"),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = day.date.dayOfMonth.toString(),
                color = when {
                    !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    isToday -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
            )
            Spacer(Modifier.height(2.dp))
            val plantCount = entry?.plants?.size ?: 0
            if (inMonth && plantCount > 0) {
                val isOverdueBadge = isToday && entry?.containsOverdue == true
                val badgeColor = if (isOverdueBadge) OverdueRed else SageGreen
                val badgeDescription = pluralStringResource(R.plurals.calendar_badge_cd, plantCount, plantCount)
                val overdueStateDescription = stringResource(R.string.calendar_badge_state_overdue)
                val badgeTag = "calendar_badge_${day.date}"
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                        // Semantics modifiers on one node are folded tail-to-head, and
                        // clearAndSetSemantics resets whatever was folded in before it (i.e.
                        // anything later/more-tail in this chain). testTag must therefore come
                        // before clearAndSetSemantics so it survives the reset instead of being
                        // wiped by it.
                        .testTag(badgeTag)
                        .clearAndSetSemantics {
                            contentDescription = badgeDescription
                            if (isOverdueBadge) stateDescription = overdueStateDescription
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = plantCount.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } else {
                Spacer(Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDaySheet(
    day: LocalDate,
    today: LocalDate,
    plants: List<PlantDayInfo>,
    onDismiss: () -> Unit,
    onNavigateToPlant: (Long) -> Unit,
    onQuickWater: (PlantCareStatus) -> Unit,
    onQuickFertilize: (PlantCareStatus) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        val locale = LocalConfiguration.current.locales[0]
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            val title = if (day == today) {
                stringResource(R.string.date_group_today)
            } else {
                day.format(DateTimeFormatter.ofPattern("EEEE, MMM d", locale))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (day == today) {
                val (overdue, dueToday) = plants.partition { it.status.isOverdue || it.status.isFertilizingOverdue }
                val sortedOverdue = overdue.sortedBy { it.status.plant.name.lowercase() }
                val sortedToday = dueToday.sortedBy { it.status.plant.name.lowercase() }
                if (sortedOverdue.isNotEmpty()) {
                    CalendarDaySheetSectionHeader(stringResource(R.string.date_group_overdue))
                    sortedOverdue.forEach { info ->
                        CalendarDayPlantRow(info, onNavigateToPlant, onQuickWater, onQuickFertilize)
                    }
                }
                if (sortedToday.isNotEmpty()) {
                    CalendarDaySheetSectionHeader(stringResource(R.string.date_group_today))
                    sortedToday.forEach { info ->
                        CalendarDayPlantRow(info, onNavigateToPlant, onQuickWater, onQuickFertilize)
                    }
                }
            } else {
                plants.sortedBy { it.status.plant.name.lowercase() }.forEach { info ->
                    CalendarDayPlantRow(info, onNavigateToPlant, onQuickWater, onQuickFertilize)
                }
            }
        }
    }
}

@Composable
private fun CalendarDaySheetSectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun CalendarDayPlantRow(
    info: PlantDayInfo,
    onNavigateToPlant: (Long) -> Unit,
    onQuickWater: (PlantCareStatus) -> Unit,
    onQuickFertilize: (PlantCareStatus) -> Unit
) {
    val plant = info.status.plant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToPlant(plant.id) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlantPhoto(uri = plant.coverPhotoUri, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = plant.name, style = MaterialTheme.typography.titleMedium)
            plant.room?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                if (info.waterDue) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.calendar_chip_water)) },
                        leadingIcon = {
                            Icon(Icons.Filled.WaterDrop, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                        }
                    )
                }
                if (info.fertilizeDue) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.calendar_chip_fertilize)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Spa, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                        }
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (plant.useLiquidFertilizer) {
            IconButton(onClick = { onQuickFertilize(info.status) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WaterDrop, stringResource(R.string.quick_log_fertilize_cd), Modifier.size(16.dp))
                    Icon(Icons.Filled.Spa, null, Modifier.size(16.dp))
                }
            }
        } else {
            IconButton(onClick = { onQuickWater(info.status) }) {
                Icon(Icons.Filled.WaterDrop, stringResource(R.string.quick_log_water_cd), Modifier.size(20.dp))
            }
            IconButton(onClick = { onQuickFertilize(info.status) }) {
                Icon(Icons.Filled.Spa, stringResource(R.string.quick_log_fertilize_cd), Modifier.size(20.dp))
            }
        }
    }
}
