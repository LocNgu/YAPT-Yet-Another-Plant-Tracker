package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.yapt.planttracker.ui.components.CareCountdownChips
import com.yapt.planttracker.ui.components.CareLogItem
import com.yapt.planttracker.ui.components.EmptyStateView
import com.yapt.planttracker.ui.components.PhotoGallery
import com.yapt.planttracker.ui.components.StatsRow

@OptIn(ExperimentalMaterial3Api::class)
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

    val snackbarHostState = remember { SnackbarHostState() }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(plant?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit plant")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToAddLog) {
                Icon(Icons.Filled.Add, contentDescription = "Log care")
            }
        }
    ) { padding ->
        if (plant == null) return@Scaffold

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                plant?.coverPhotoUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "Plant cover photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
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
                    Spacer(Modifier.height(8.dp))
                    CareCountdownChips(status = status)
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (photoLogs.isNotEmpty()) {
                item {
                    Text(
                        text = "Photos",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    PhotoGallery(
                        photoLogs = photoLogs,
                        onPhotoClick = {}
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            item {
                Text(
                    text = "Care History",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
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
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }
}
