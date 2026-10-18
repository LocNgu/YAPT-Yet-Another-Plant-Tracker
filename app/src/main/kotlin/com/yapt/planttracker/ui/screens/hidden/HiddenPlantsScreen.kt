package com.yapt.planttracker.ui.screens.hidden

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.ui.components.EmptyStateView
import com.yapt.planttracker.ui.components.PlantPhoto
import com.yapt.planttracker.ui.util.relativeDateText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenPlantsScreen(
    viewModel: HiddenPlantsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPlant: (Long) -> Unit
) {
    val hiddenPlants by viewModel.hiddenPlants.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val unhiddenMsg = stringResource(R.string.snackbar_plant_unhidden)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HiddenPlantsViewModel.Event.Unhidden -> snackbarHostState.showSnackbar(unhiddenMsg)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hidden_plants_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (hiddenPlants.isEmpty()) {
            EmptyStateView(
                message = stringResource(R.string.hidden_plants_empty_state),
                icon = Icons.Filled.VisibilityOff,
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
                items(hiddenPlants, key = { it.id }) { plant ->
                    HiddenPlantRow(
                        plant = plant,
                        onOpen = { onNavigateToPlant(plant.id) },
                        onUnhide = { viewModel.unhidePlant(plant.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HiddenPlantRow(
    plant: Plant,
    onOpen: () -> Unit,
    onUnhide: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClick = onOpen),
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
                        text = relativeDateText(plant.hiddenAt!!, maxRelativeDays = 14),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(onClick = onUnhide) {
                Text(stringResource(R.string.unhide_button))
            }
        }
    }
}
