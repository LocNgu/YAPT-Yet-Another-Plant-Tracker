package com.yapt.planttracker.ui.screens.repotting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.preferences.RepottingOverviewPreferences
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.repotting.RepottingOverview
import com.yapt.planttracker.domain.repotting.RepottingOverviewThreshold
import com.yapt.planttracker.domain.repotting.observeRepottingOverview
import com.yapt.planttracker.domain.schedule.RepotPlanSeason
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.dayChangeTicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

sealed interface RepottingOverviewUiState {
    data object Loading : RepottingOverviewUiState

    data class Ready(
        val threshold: RepottingOverviewThreshold,
        val overview: RepottingOverview,
        val hasActivePlants: Boolean
    ) : RepottingOverviewUiState
}

/**
 * The Repotting overview page (#525, product ADR-0059). The list is [observeRepottingOverview] — the
 * same pipeline Settings' count uses — so it moves by itself after a repot, a plan change, or
 * midnight ([dayChange], #550). The selected chip is the saved threshold: tapping one writes it and the
 * chip row reads it back, so it survives leaving the page and process death.
 */
class RepottingOverviewViewModel(
    private val plantRepository: PlantRepository,
    careLogRepository: CareLogRepository,
    private val preferences: RepottingOverviewPreferences,
    private val quickLogUseCase: QuickLogUseCase,
    dayChange: Flow<LocalDate> = dayChangeTicker(),
    private val nowProvider: () -> Long = System::currentTimeMillis
) : ViewModel() {

    val uiState: StateFlow<RepottingOverviewUiState> = observeRepottingOverview(
        plants = plantRepository.getAllPlants(),
        lastRepotAtByPlantId = careLogRepository.observeLastCareAtByPlant(CareType.REPOT),
        threshold = preferences.threshold,
        today = dayChange
    )
        .map<_, RepottingOverviewUiState> {
            RepottingOverviewUiState.Ready(it.threshold, it.overview, it.hasActivePlants)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RepottingOverviewUiState.Loading)

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // Serializes this page's own writes. A repot's lifecycle reset is a full-row write of a plant it read
    // just before, so a plan write landing in between would be overwritten with the old plan; one at a
    // time per page keeps them ordered. Plant Detail does the same with its own intervalEditMutex.
    private val actionMutex = Mutex()

    fun selectThreshold(threshold: RepottingOverviewThreshold) {
        viewModelScope.launch { preferences.setThreshold(threshold) }
    }

    /** Logs a REPOT through [QuickLogUseCase.quickLog], which also clears a plan the log supersedes. */
    fun repot(plantId: Long, loggedAt: Long) {
        perform(plantId) { plant ->
            Event.Message(quickLogUseCase.quickLog(plant, CareType.REPOT, loggedAt).message)
        }
    }

    fun planRepot(plantId: Long, season: RepotPlanSeason) {
        perform(plantId) { plant ->
            val now = nowProvider()
            plantRepository.setRepotPlan(plant.id, season.startAtMillis, now, now)
            Event.PlanSaved(plant.name)
        }
    }

    fun clearPlan(plantId: Long) {
        perform(plantId) { plant ->
            plantRepository.clearRepotPlan(plant.id, nowProvider())
            Event.PlanCleared(plant.name)
        }
    }

    // The plant is re-read inside the lock: the row the user tapped may be a snapshot older than a
    // write that just finished, and a plant archived or deleted meanwhile is simply skipped.
    private fun perform(plantId: Long, action: suspend (Plant) -> Event) {
        viewModelScope.launch {
            val event = actionMutex.withLock {
                try {
                    plantRepository.getPlantById(plantId).first()?.let { action(it) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    Event.ActionFailed
                }
            }
            event?.let { _events.emit(it) }
        }
    }

    sealed interface Event {
        data class Message(val text: String) : Event
        data class PlanSaved(val plantName: String) : Event
        data class PlanCleared(val plantName: String) : Event
        data object ActionFailed : Event
    }

    class Factory(
        private val plantRepository: PlantRepository,
        private val careLogRepository: CareLogRepository,
        private val preferences: RepottingOverviewPreferences,
        private val quickLogUseCase: QuickLogUseCase
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RepottingOverviewViewModel(
            plantRepository,
            careLogRepository,
            preferences,
            quickLogUseCase
        ) as T
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5000L
    }
}
