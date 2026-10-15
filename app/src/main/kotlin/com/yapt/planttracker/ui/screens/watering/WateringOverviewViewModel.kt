package com.yapt.planttracker.ui.screens.watering

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeFlow
import com.yapt.planttracker.domain.watering.WateringOverview
import com.yapt.planttracker.domain.watering.observeWateringOverview
import com.yapt.planttracker.util.dayChangeTicker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

sealed interface WateringOverviewUiState {
    data object Loading : WateringOverviewUiState

    data class Ready(
        val overview: WateringOverview,
        val hasActivePlants: Boolean
    ) : WateringOverviewUiState
}

/**
 * The Watering overview page (#901, product ADR-0064): a read-only audit of how often each plant needs
 * water. The state is [observeWateringOverview], so it moves by itself after a watering is logged, a
 * plant is edited, or the seasonal amplitude changes. [dayChange] re-evaluates the groups and due dates
 * on a day change, but the page is not guaranteed to refresh while open across midnight (a snapshot equal to
 * the previous one is conflated by `stateIn`).
 */
class WateringOverviewViewModel(
    plantRepository: PlantRepository,
    careLogRepository: CareLogRepository,
    seasonalAmplitude: Flow<Double>,
    dayChange: Flow<LocalDate> = dayChangeTicker(),
    nowProvider: () -> Long = System::currentTimeMillis,
    hemisphere: () -> Hemisphere = SeasonalWatering::currentHemisphere
) : ViewModel() {

    val uiState: StateFlow<WateringOverviewUiState> = observeWateringOverview(
        plants = plantRepository.getAllPlants(),
        lastWateredAtByPlantId = careLogRepository.observeLastCareAtByPlant(CareType.WATER),
        seasonalAmplitude = seasonalAmplitude,
        dayChange = dayChange,
        nowProvider = nowProvider,
        hemisphere = hemisphere
    )
        .map<_, WateringOverviewUiState> { WateringOverviewUiState.Ready(it.overview, it.hasActivePlants) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WateringOverviewUiState.Loading)

    class Factory(
        private val plantRepository: PlantRepository,
        private val careLogRepository: CareLogRepository,
        private val dataStore: DataStore<Preferences>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WateringOverviewViewModel(
            plantRepository,
            careLogRepository,
            dataStore.seasonalAmplitudeFlow()
        ) as T
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5000L
    }
}
