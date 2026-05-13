package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.yapt.planttracker.ui.components.CareLogItem
import com.yapt.planttracker.ui.components.EmptyStateView
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
    val photoLogs by viewModel.photoLogs.collectAsStateWithLifecycle()
    val careStatus by viewModel.careStatus.collectAsStateWithLifecycle()
    val suggestedInterval by viewModel.suggestedWateringInterval.collectAsStateWithLifecycle()
    val selectedTimeRange by viewModel.selectedTimeRange.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    val hasPhoto = plant?.coverPhotoUri != null
    val iconTint = if (hasPhoto) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    val iconContainerColor = if (hasPhoto) Color.Black.copy(alpha = 0.60f) else Color.Transparent

    LaunchedEffect(suggestedInterval) {
        suggestedInterval?.let { interval ->
            val result = snackbarHostState.showSnackbar(
                message = "Suggested: water every $interval days",
                actionLabel = "Apply",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.applySuggestedInterval(interval)
            } else {
                viewModel.clearSuggestedInterval()
            }
        }
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
                                contentDescription = "Plant cover photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
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
                                text = "📍 $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        plant?.wateringIntervalDays?.let {
                            Text(
                                text = "💧 Every $it days",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                careStatus?.let { status ->
                    item {
                        StatsRow(status = status)
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

                val photoUris = photoLogs.mapNotNull { it.photoUri }
                if (photoUris.isNotEmpty()) {
                    item {
                        Text(
                            text = "Photos",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        PhotoGallery(
                            photoUris = photoUris,
                            onPhotoClick = {}
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
                            text = "Care History",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${careLogs.size} logs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (careLogs.isEmpty()) {
                    item {
                        Box(modifier = Modifier.height(200.dp)) {
                            EmptyStateView(
                                message = "No care logged yet.\nTap + to log your first care event.",
                                icon = Icons.Filled.Notes
                            )
                        }
                    }
                } else {
                    items(careLogs, key = { it.id }) { log ->
                        CareLogItem(
                            log = log,
                            onEdit = { onNavigateToEditLog(log.id) },
                            onDelete = { viewModel.deleteLog(log) }
                        )
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
                    contentDescription = "Back",
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
                    contentDescription = "Edit plant",
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
            Icon(Icons.Filled.Add, contentDescription = "Log care")
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
        }
    }
}
