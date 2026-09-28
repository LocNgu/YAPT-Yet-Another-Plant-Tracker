package com.yapt.planttracker.ui.screens.today

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.TodayCareRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.model.WateringReason
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayQueueSnapshot
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

sealed interface TodayUiState {
    data object Loading : TodayUiState
    data class Ready(val snapshot: TodayQueueSnapshot, val groupByPlant: Boolean) : TodayUiState
    data object Error : TodayUiState
}

sealed interface TodayNavigationEvent {
    data class PlantDetail(val plantId: Long) : TodayNavigationEvent
    data object AddPlant : TodayNavigationEvent
}

@Suppress("LongParameterList", "TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val application: Application,
    private val todayCareRepository: TodayCareRepository,
    featureFlags: FeatureFlags,
    private val quickLogUseCase: QuickLogUseCase,
    private val plantRepository: PlantRepository
) : ViewModel() {

    private sealed interface QueueResult {
        data object Loading : QueueResult
        data class Success(val snapshot: TodayQueueSnapshot) : QueueResult
        data object Error : QueueResult
    }

    private val _selectedTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTaskIds: StateFlow<Set<String>> = _selectedTaskIds.asStateFlow()
    private val bulkCompletionInFlight = AtomicBoolean(false)

    private val retryGeneration = MutableStateFlow(0)
    private val queueResult = retryGeneration.flatMapLatest {
        todayCareRepository.observeQueue()
            .map<TodayQueueSnapshot, QueueResult> { QueueResult.Success(it) }
            .catch { emit(QueueResult.Error) }
            .onStart { emit(QueueResult.Loading) }
    }.onEach { result ->
        if (result is QueueResult.Success) reconcileSelection(result.snapshot.tasks)
    }

    val uiState: StateFlow<TodayUiState> = combine(
        queueResult,
        featureFlags.isEnabled(FeatureFlagRegistry.TODAY_GROUP_BY_PLANT)
    ) { result, groupByPlant ->
        when (result) {
            QueueResult.Loading -> TodayUiState.Loading
            QueueResult.Error -> TodayUiState.Error
            is QueueResult.Success -> TodayUiState.Ready(result.snapshot, groupByPlant)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodayUiState.Loading)

    private val _messageEvent = MutableSharedFlow<String>()
    val messageEvent: SharedFlow<String> = _messageEvent.asSharedFlow()

    private val _wateringSuggestion = MutableSharedFlow<QuickWaterSuggestion>()
    val wateringSuggestion: SharedFlow<QuickWaterSuggestion> = _wateringSuggestion.asSharedFlow()

    private val _navigationEvent = MutableSharedFlow<TodayNavigationEvent>()
    val navigationEvent: SharedFlow<TodayNavigationEvent> = _navigationEvent.asSharedFlow()

    fun retry() {
        retryGeneration.value += 1
    }

    fun openPlant(plantId: Long) {
        viewModelScope.launch { _navigationEvent.emit(TodayNavigationEvent.PlantDetail(plantId)) }
    }

    fun addPlant() {
        viewModelScope.launch { _navigationEvent.emit(TodayNavigationEvent.AddPlant) }
    }

    fun toggleTaskSelection(taskId: String) {
        val task = currentTasks().firstOrNull { it.id == taskId && it.isBulkEligible } ?: return
        _selectedTaskIds.value = _selectedTaskIds.value.let { selected ->
            if (task.id in selected) selected - task.id else selected + task.id
        }
    }

    fun togglePlantSelection(plantId: Long) {
        val ids = currentTasks().filter { it.plant.id == plantId && it.isBulkEligible }.map { it.id }.toSet()
        if (ids.isEmpty()) return
        _selectedTaskIds.value = if (ids.all { it in _selectedTaskIds.value }) {
            _selectedTaskIds.value - ids
        } else {
            _selectedTaskIds.value + ids
        }
    }

    fun selectAll() {
        _selectedTaskIds.value = currentTasks().filter { it.isBulkEligible }.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selectedTaskIds.value = emptySet()
    }

    fun completeSelected() {
        val tasks = currentTasks().filter { it.id in _selectedTaskIds.value && it.isBulkEligible }
        if (tasks.isEmpty()) {
            clearSelection()
            return
        }
        if (!bulkCompletionInFlight.compareAndSet(false, true)) return
        val claimedIds = tasks.mapTo(mutableSetOf()) { it.id }
        _selectedTaskIds.value = emptySet()
        viewModelScope.launch {
            try {
                val result = quickLogUseCase.completeTodayTasks(tasks)
                _messageEvent.emit(
                    application.getString(
                        R.string.today_bulk_result,
                        result.completedCount,
                        result.totalCount,
                        result.skippedCount
                    )
                )
            } catch (error: CancellationException) {
                restoreSelection(claimedIds)
                throw error
            } catch (_: Exception) {
                restoreSelection(claimedIds)
                _messageEvent.emit(application.getString(R.string.today_action_failed))
            } finally {
                bulkCompletionInFlight.set(false)
            }
        }
    }

    fun completeWater(taskId: String, reason: WateringReason?) {
        val task = task(taskId) ?: return
        viewModelScope.launch {
            executeQuickAction { quickLogUseCase.quickWaterWithReason(task.plant, reason) }
        }
    }

    fun completeFertilizing(taskId: String, reason: WateringReason?) {
        val task = task(taskId) ?: return
        viewModelScope.launch {
            executeQuickAction {
                if (task.kind == TodayCareKind.WATER_AND_FERTILIZE) {
                    quickLogUseCase.quickLiquidFertilizeWithReason(task.plant, reason)
                } else {
                    quickLogUseCase.quickLog(task.plant, CareType.FERTILIZE)
                }
            }
        }
    }

    fun completeRepotting(taskId: String, loggedAt: Long) {
        val task = task(taskId) ?: return
        viewModelScope.launch {
            executeQuickAction { quickLogUseCase.quickLog(task.plant, CareType.REPOT, loggedAt) }
        }
    }

    fun completeCustomReminder(taskId: String) {
        val task = task(taskId) ?: return
        viewModelScope.launch {
            runCatching { quickLogUseCase.completeCustomReminder(task) }
                .onSuccess { _messageEvent.emit(it.message) }
                .onFailure { _messageEvent.emit(application.getString(R.string.today_action_failed)) }
        }
    }

    fun rescheduleWatering(taskId: String, dueAt: Long) {
        val task = task(taskId) ?: return
        viewModelScope.launch {
            executeAction { quickLogUseCase.recordReschedule(task.plant, dueAt) }
        }
    }

    fun savePhoto(plantId: Long, uri: Uri) {
        viewModelScope.launch {
            executeAction {
                val plant = requireNotNull(quickLogUseCase.saveReminderPhoto(plantId, uri.toString()))
                _messageEvent.emit(application.getString(R.string.today_photo_saved, plant.name))
            }
        }
    }

    fun applySuggestedInterval(
        suggestion: QuickWaterSuggestion,
        newInterval: Int,
        suggestedBaseInterval: Double?
    ) {
        viewModelScope.launch {
            executeAction {
                plantRepository.getPlantById(suggestion.plantId).first()?.let { plant ->
                    quickLogUseCase.applyWateringIntervalSuggestion(
                        plant,
                        suggestion.suggestedInterval,
                        newInterval,
                        suggestedBaseInterval
                    )
                }
            }
        }
    }

    fun dismissSuggestedInterval(plantId: Long) {
        viewModelScope.launch {
            executeAction {
                plantRepository.getPlantById(plantId).first()?.let { plant ->
                    quickLogUseCase.recordWateringSuggestionDismissal(plant)
                }
            }
        }
    }

    private suspend fun executeQuickAction(action: suspend () -> QuickLogUseCase.QuickLogOutcome) {
        try {
            val outcome = action()
            outcome.suggestion?.let { _wateringSuggestion.emit(it) }
            _messageEvent.emit(outcome.message)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _messageEvent.emit(application.getString(R.string.today_action_failed))
        }
    }

    private suspend fun executeAction(action: suspend () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _messageEvent.emit(application.getString(R.string.today_action_failed))
        }
    }

    private fun task(taskId: String): TodayCareTask? = currentTasks().firstOrNull { it.id == taskId }

    private fun reconcileSelection(tasks: List<TodayCareTask>) {
        val eligibleIds = tasks.asSequence().filter { it.isBulkEligible }.map { it.id }.toSet()
        _selectedTaskIds.value = _selectedTaskIds.value.intersect(eligibleIds)
    }

    private fun restoreSelection(taskIds: Set<String>) {
        val ready = uiState.value as? TodayUiState.Ready
        _selectedTaskIds.value = if (ready == null) {
            taskIds
        } else {
            val eligibleIds = ready.snapshot.tasks.asSequence()
                .filter { it.isBulkEligible }
                .map { it.id }
                .toSet()
            taskIds.intersect(eligibleIds)
        }
    }

    private fun currentTasks(): List<TodayCareTask> =
        (uiState.value as? TodayUiState.Ready)?.snapshot?.tasks.orEmpty()

    @Suppress("LongParameterList")
    class Factory(
        private val application: Application,
        private val todayCareRepository: TodayCareRepository,
        private val featureFlags: FeatureFlags,
        private val quickLogUseCase: QuickLogUseCase,
        private val plantRepository: PlantRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TodayViewModel(
            application,
            todayCareRepository,
            featureFlags,
            quickLogUseCase,
            plantRepository
        ) as T
    }
}
