package com.yapt.planttracker.ui.screens.away

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.SitterVisitState
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Confirms a planned sitter visit from Care's after-the-trip prompt and from Settings (product ADR-0065, slice 5).
 * It reuses slice 4's checklist ([ConfirmVisitContent]) and the same write: [QuickLogUseCase.logSitterVisitWaterings]
 * then the stored visit state, so there is no reason prompt, no post-watering reminder and nothing writes a plant row.
 */
class ConfirmVisitViewModel(
    private val repository: AwayPeriodRepository,
    private val plantRepository: PlantRepository,
    private val quickLogUseCase: QuickLogUseCase
) : ViewModel() {

    private val selectedVisitId = MutableStateFlow<Long?>(null)
    private val unticked = MutableStateFlow<Set<Long>>(emptySet())
    private val mutationLock = Mutex()

    private val _events = MutableSharedFlow<PlanVisitEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<PlanVisitEvent> = _events.asSharedFlow()

    /** The checklist to show, or null while no visit is open (or the open one has been answered). */
    val uiState: StateFlow<PlanVisitUiState.Confirm?> = combine(
        selectedVisitId,
        repository.observeAllWithVisits(),
        plantRepository.getAllPlants(),
        unticked
    ) { id, trips, plants, skipped ->
        val visit = id?.let { trips.flatMap { it.visits }.firstOrNull { visit -> visit.id == id } }
            ?.takeIf { it.state == SitterVisitState.PLANNED }
        visit?.let { confirmState(it, plants, skipped) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun open(visitId: Long) {
        unticked.value = emptySet()
        selectedVisitId.value = visitId
    }

    fun close() {
        selectedVisitId.value = null
        unticked.value = emptySet()
    }

    fun toggleRow(plantId: Long) {
        unticked.update { if (plantId in it) it - plantId else it + plantId }
    }

    fun confirm(): Job = viewModelScope.launch {
        mutationLock.withLock {
            val confirm = currentConfirm() ?: return@withLock
            val ticked = confirm.rows.filter { it.ticked }
            val tickedIds = ticked.mapTo(HashSet()) { it.plant.id }
            val result = quickLogUseCase.logSitterVisitWaterings(ticked.map { it.plant }, confirm.visit.day)
            repository.updateVisit(
                confirm.visit.copy(
                    state = SitterVisitState.CONFIRMED,
                    plants = confirm.visit.plants.map { it.copy(watered = it.plantId in tickedIds) }
                )
            )
            close()
            _events.tryEmit(PlanVisitEvent.Confirmed(result.loggedCount, result.skippedCount))
        }
    }

    fun didNotHappen(): Job = viewModelScope.launch {
        mutationLock.withLock {
            val confirm = currentConfirm() ?: return@withLock
            repository.updateVisit(confirm.visit.copy(state = SitterVisitState.DID_NOT_HAPPEN))
            close()
            _events.tryEmit(PlanVisitEvent.DidNotHappen)
        }
    }

    /** Re-read from storage inside the lock, so a double tap or an answer given elsewhere can't write twice. */
    private suspend fun currentConfirm(): PlanVisitUiState.Confirm? {
        val id = selectedVisitId.value
        val visit = repository.getAllWithVisits().flatMap { it.visits }
            .firstOrNull { it.id == id && it.state == SitterVisitState.PLANNED }
        return visit?.let { confirmState(it, plantRepository.getAllPlants().first(), unticked.value) }
    }

    class Factory(
        private val repository: AwayPeriodRepository,
        private val plantRepository: PlantRepository,
        private val quickLogUseCase: QuickLogUseCase
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConfirmVisitViewModel(repository, plantRepository, quickLogUseCase) as T
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5000L
        const val EVENT_BUFFER = 8
    }
}
