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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.ui.components.EmptyStateView
import com.yapt.planttracker.ui.components.PlantCard

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
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val selectedRoom by viewModel.selectedRoom.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sortMenuExpanded by remember { mutableStateOf(false) }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("My Plants") },
                colors = TopAppBarDefaults.topAppBarColors(),
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort plants")
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            SortOption.entries.forEach { option ->
                                val isActive = sortOrder.option == option
                                val label = when (option) {
                                    SortOption.ALPHABETICAL -> if (isActive) {
                                        if (sortOrder.direction == SortDirection.ASC) "Alphabetical (A→Z)" else "Alphabetical (Z→A)"
                                    } else "Alphabetical"
                                    SortOption.WATERING_DUE -> "Watering due"
                                    SortOption.FERTILIZING_DUE -> "Fertilizing due"
                                    SortOption.RECENTLY_ADDED -> "Recently added"
                                    SortOption.BOTH_DUE -> "Water + Fertilize due"
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
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add Plant") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (rooms.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedRoom == null,
                            onClick = { viewModel.selectRoom(null) },
                            label = { Text("All") }
                        )
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
                val emptyMessage = if (sortOrder.option == SortOption.BOTH_DUE) {
                    "No plants need both watering\nand fertilizing right now."
                } else {
                    "No plants yet!\nTap + to add your first plant."
                }
                EmptyStateView(
                    message = emptyMessage,
                    icon = Icons.Filled.LocalFlorist
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(plantsWithStatus) { status ->
                        PlantCard(
                            status = status,
                            onClick = { onNavigateToPlant(status.plant.id) },
                            onQuickWater = { viewModel.quickLog(status.plant.id, CareType.WATER) },
                            onQuickFertilize = { viewModel.quickLog(status.plant.id, CareType.FERTILIZE) }
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}
