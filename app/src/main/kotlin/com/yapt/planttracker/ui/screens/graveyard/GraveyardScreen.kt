package com.yapt.planttracker.ui.screens.graveyard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.ui.components.EmptyStateView
import com.yapt.planttracker.ui.components.PlantPhoto
import com.yapt.planttracker.util.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraveyardScreen(
    viewModel: GraveyardViewModel,
    onNavigateBack: () -> Unit
) {
    val archivedPlants by viewModel.archivedPlants.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var overflowExpanded by remember { mutableStateOf(false) }
    var plantToDelete by remember { mutableStateOf<Plant?>(null) }
    var showEmptyGraveyardDialog by remember { mutableStateOf(false) }

    val restoredMsg = stringResource(R.string.graveyard_restored_snackbar)
    val deletedMsg = stringResource(R.string.graveyard_deleted_snackbar)
    val emptiedMsg = stringResource(R.string.graveyard_emptied_snackbar)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is GraveyardViewModel.Event.Restored -> restoredMsg
                is GraveyardViewModel.Event.Deleted -> deletedMsg
                is GraveyardViewModel.Event.GraveyardEmptied -> emptiedMsg
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    plantToDelete?.let { plant ->
        AlertDialog(
            onDismissRequest = { plantToDelete = null },
            title = { Text(stringResource(R.string.graveyard_delete_permanent_title)) },
            text = { Text(stringResource(R.string.graveyard_delete_permanent_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePermanently(plant)
                    plantToDelete = null
                }) {
                    Text(
                        stringResource(R.string.graveyard_delete_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { plantToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showEmptyGraveyardDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyGraveyardDialog = false },
            title = { Text(stringResource(R.string.graveyard_empty_title)) },
            text = { Text(stringResource(R.string.graveyard_empty_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.emptyGraveyard()
                    showEmptyGraveyardDialog = false
                }) {
                    Text(
                        stringResource(R.string.graveyard_delete_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyGraveyardDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.graveyard_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.graveyard_overflow_empty)) },
                                onClick = {
                                    overflowExpanded = false
                                    showEmptyGraveyardDialog = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (archivedPlants.isEmpty()) {
            EmptyStateView(
                message = stringResource(R.string.graveyard_empty_state),
                icon = Icons.Filled.DeleteForever,
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(archivedPlants, key = { it.id }) { plant ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlantPhoto(
                                uri = plant.coverPhotoUri,
                                size = 56.dp,
                                rounded = true
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = plant.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = DateUtils.formatRelative(plant.archivedAt!!, maxRelativeDays = 14),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { viewModel.restorePlant(plant.id) }) {
                                Text(stringResource(R.string.graveyard_restore_button))
                            }
                            IconButton(onClick = { plantToDelete = plant }) {
                                Icon(
                                    Icons.Filled.DeleteForever,
                                    contentDescription = stringResource(R.string.cd_graveyard_delete_forever),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
