package com.yapt.planttracker.ui.screens.away

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.away.AwayPeriods
import com.yapt.planttracker.domain.away.RhythmEvaluation
import com.yapt.planttracker.domain.away.SitterRhythm
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeFlow
import com.yapt.planttracker.util.dayChangeTicker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

sealed interface SitterRhythmUiState {
    data object Loading : SitterRhythmUiState
    data object NoTrip : SitterRhythmUiState
    data class Ready(
        val trip: AwayPeriod,
        val evaluation: RhythmEvaluation,
        /** The rhythm saved on the trip, or `null` when none was chosen yet. */
        val savedEveryDays: Int?
    ) : SitterRhythmUiState
}

/**
 * Trip setup's "How often should a sitter come?" step (product ADR-0065). The state is pure
 * [SitterRhythm.evaluate] over the live plants, so it follows an edit to a plant or the seasonal setting; the
 * choice is the only thing written, and only to the trip row ([AwayPeriod.visitEveryDays]), never to a plant.
 */
class SitterRhythmViewModel(
    private val repository: AwayPeriodRepository,
    plantRepository: PlantRepository,
    seasonalAmplitude: Flow<Double>,
    dayChange: Flow<LocalDate> = dayChangeTicker(),
    hemisphere: () -> Hemisphere = SeasonalWatering::currentHemisphere,
    private val todayProvider: () -> LocalDate = { LocalDate.now() }
) : ViewModel() {

    val uiState: StateFlow<SitterRhythmUiState> = combine(
        repository.observeAll(),
        plantRepository.getAllPlants(),
        seasonalAmplitude,
        dayChange
    ) { periods, plants, amplitude, today ->
        val trip = AwayPeriods.currentOrUpcoming(periods, today)
        if (trip == null) {
            SitterRhythmUiState.NoTrip
        } else {
            SitterRhythmUiState.Ready(
                trip = trip,
                evaluation = SitterRhythm.evaluate(plants, trip.firstDay, amplitude, hemisphere()),
                savedEveryDays = trip.visitEveryDays
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SitterRhythmUiState.Loading)

    private val mutationLock = Mutex()

    fun save(everyDays: Int): Job = viewModelScope.launch {
        mutationLock.withLock {
            if (everyDays !in SitterRhythm.OPTIONS) return@withLock
            val trip = AwayPeriods.currentOrUpcoming(repository.getAll(), todayProvider()) ?: return@withLock
            repository.updatePeriod(trip.copy(visitEveryDays = everyDays))
        }
    }

    class Factory(
        private val repository: AwayPeriodRepository,
        private val plantRepository: PlantRepository,
        private val dataStore: DataStore<Preferences>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SitterRhythmViewModel(repository, plantRepository, dataStore.seasonalAmplitudeFlow()) as T
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5000L
    }
}
