package com.yapt.planttracker.ui.screens.watering

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.ui.components.EmptyStateView
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab

@Composable
fun WateringOverviewScreen(
    viewModel: WateringOverviewViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPlant: (plantId: Long, tab: PlantDetailTab) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { WateringOverviewTopBar(onNavigateBack) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                WateringOverviewUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is WateringOverviewUiState.Ready ->
                    if (state.hasActivePlants) {
                        WateringOverviewContent(
                            overview = state.overview,
                            onOpenPlant = { onNavigateToPlant(it, PlantDetailTab.WATER) }
                        )
                    } else {
                        EmptyStateView(
                            message = stringResource(R.string.watering_overview_no_plants),
                            icon = Icons.Filled.WaterDrop
                        )
                    }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WateringOverviewTopBar(onNavigateBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.watering_overview_title)) },
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
