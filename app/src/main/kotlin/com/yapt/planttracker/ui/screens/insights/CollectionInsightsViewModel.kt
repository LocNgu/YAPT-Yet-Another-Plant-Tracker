package com.yapt.planttracker.ui.screens.insights

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.insights.CollectionInsights
import com.yapt.planttracker.domain.insights.observeCollectionInsights
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeFlow
import com.yapt.planttracker.util.dayChangeTicker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

sealed interface CollectionInsightsUiState {
    data object Loading : CollectionInsightsUiState

    data class Ready(
        val insights: CollectionInsights,
        val hasActivePlants: Boolean
    ) : CollectionInsightsUiState
}

/**
 * The Collection insights page (#1004, product ADR-0089): a read-only, rolling-30-day summary that points at
 * an action. [dayChange] moves the window at midnight; an unchanged snapshot is conflated by `stateIn`.
 */
@Suppress("LongParameterList")
class CollectionInsightsViewModel(
    plantRepository: PlantRepository,
    careLogRepository: CareLogRepository,
    plantIssueRepository: PlantIssueRepository,
    awayPeriodRepository: AwayPeriodRepository,
    seasonalAmplitude: Flow<Double>,
    dayChange: Flow<LocalDate> = dayChangeTicker(),
    nowProvider: () -> Long = System::currentTimeMillis,
    hemisphere: () -> Hemisphere = SeasonalWatering::currentHemisphere
) : ViewModel() {

    val uiState: StateFlow<CollectionInsightsUiState> = observeCollectionInsights(
        plants = plantRepository.getAllPlants(),
        careLogs = careLogRepository.getAllLogs(),
        issues = plantIssueRepository.getAllIssues(),
        awayPeriods = awayPeriodRepository.observeAll(),
        seasonalAmplitude = seasonalAmplitude,
        dayChange = dayChange,
        nowProvider = nowProvider,
        hemisphere = hemisphere
    )
        .map<_, CollectionInsightsUiState> { CollectionInsightsUiState.Ready(it.insights, it.hasActivePlants) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CollectionInsightsUiState.Loading)

    class Factory(
        private val plantRepository: PlantRepository,
        private val careLogRepository: CareLogRepository,
        private val plantIssueRepository: PlantIssueRepository,
        private val awayPeriodRepository: AwayPeriodRepository,
        private val dataStore: DataStore<Preferences>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CollectionInsightsViewModel(
            plantRepository,
            careLogRepository,
            plantIssueRepository,
            awayPeriodRepository,
            dataStore.seasonalAmplitudeFlow()
        ) as T
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5000L
    }
}
