package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.yapt.planttracker.R
import com.yapt.planttracker.ui.components.CareLogItem
import com.yapt.planttracker.ui.components.EmptyStateView
import com.yapt.planttracker.ui.components.FullScreenPhotoViewer
import com.yapt.planttracker.ui.components.PhotoGallery
import com.yapt.planttracker.ui.components.StatsRow
import com.yapt.planttracker.ui.components.WateringHistoryChart

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
    val galleryPhotos by viewModel.galleryPhotos.collectAsStateWithLifecycle()
    val careStatus by viewModel.careStatus.collectAsStateWithLifecycle()
    val suggestedInterval by viewModel.suggestedWateringInterval.collectAsStateWithLifecycle()
    val selectedTimeRange by viewModel.selectedTimeRange.collectAsStateWithLifecycle()
    val showSkipDialog by viewModel.showSkipDialog.collectAsStateWithLifecycle()

    val hasPhoto = plant?.coverPhotoUri != null
    val iconTint = if (hasPhoto) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    val iconContainerColor = if (hasPhoto) Color.Black.copy(alpha = 0.60f) else Color.Transparent

    var fullScreenPhotoUri by remember { mutableStateOf<String?>(null) }

    var isExpanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "chevronRotation"
    )

    var intervalFieldText by remember(suggestedInterval) {
        mutableStateOf(suggestedInterval?.toString().orEmpty())
    }
    val parsedInterval = intervalFieldText.toIntOrNull()?.takeIf { it >= 1 }

    var skipDays by remember { mutableIntStateOf(1) }
    LaunchedEffect(showSkipDialog) {
        if (showSkipDialog) skipDays = 1
    }

    LaunchedEffect(suggestedInterval, plant?.wateringIntervalDays) {
        val s = suggestedInterval
        val current = plant?.wateringIntervalDays
        if (s != null && current != null && s == current) {
            viewModel.clearSuggestedInterval()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PlantDetailViewModel.Event.SkipConfirmed -> {
                    viewModel.suggestedWateringInterval.value = event.proposedInterval
                }
                else -> {}
            }
        }
    }

    fullScreenPhotoUri?.let { uri ->
        FullScreenPhotoViewer(uri = uri, onDismiss = { fullScreenPhotoUri = null })
    }

    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSkipDialog() },
            title = { Text(stringResource(R.string.skip_watering_title)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(
                        onClick = { if (skipDays > 1) skipDays-- },
                        enabled = skipDays > 1
                    ) { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.skip_watering_decrease_cd)) }
                    Text(pluralStringResource(R.plurals.skip_watering_days, skipDays, skipDays))
                    IconButton(
                        onClick = { if (skipDays < 7) skipDays++ },
                        enabled = skipDays < 7
                    ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.skip_watering_increase_cd)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmSkip(skipDays) }) { Text(stringResource(R.string.skip_watering_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSkipDialog() }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    val showDialog = suggestedInterval != null &&
        plant != null &&
        suggestedInterval != plant?.wateringIntervalDays

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.clearSuggestedInterval() },
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
                TextButton(onClick = { viewModel.clearSuggestedInterval() }) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(Modifier.fillMaxSize()) {
        if (plant != null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
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
                                    .clickable { plant?.coverPhotoUri?.let { fullScreenPhotoUri = it } }
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

                careStatus?.let { status ->
                    item {
                        StatsRow(status = status)
                        if (plant?.wateringIntervalDays != null && (status.isOverdue || status.isDueSoon)) {
                            TextButton(
                                onClick = { viewModel.requestSkip() },
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text(stringResource(R.string.skip_watering_title))
                            }
                        }
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

                if (galleryPhotos.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.plant_detail_photos_section),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        PhotoGallery(
                            photoUris = galleryPhotos.map { it.uri },
                            onPhotoClick = { uri -> fullScreenPhotoUri = uri }
                        )
                        Spacer(Modifier.height(16.dp))
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
                                icon = Icons.Filled.Notes
                            )
                        }
                    }
                } else {
                    val visibleLogs = if (isExpanded) careLogs else careLogs.take(5)
                    items(visibleLogs, key = { it.id }) { log ->
                        CareLogItem(
                            log = log,
                            onEdit = { onNavigateToEditLog(log.id) },
                            onDelete = { viewModel.deleteLog(log) }
                        )
                    }

                    if (careLogs.size > 5) {
                        item {
                            val remaining = careLogs.size - 5
                            AssistChip(
                                onClick = { isExpanded = !isExpanded },
                                label = {
                                    Text(
                                        if (isExpanded) stringResource(R.string.care_history_show_less)
                                        else pluralStringResource(R.plurals.care_history_show_more, remaining, remaining)
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.ExpandMore,
                                        contentDescription = if (isExpanded)
                                            stringResource(R.string.care_history_collapse_cd)
                                        else
                                            stringResource(R.string.care_history_expand_cd),
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

        }
    }
}
