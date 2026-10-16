package com.yapt.planttracker.ui.screens.away

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.away.SitterList
import com.yapt.planttracker.domain.away.SitterListComposer
import com.yapt.planttracker.domain.away.VisitPlanSummary
import com.yapt.planttracker.domain.away.VisitProjection
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitPlant
import com.yapt.planttracker.domain.model.SitterVisitState
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeFlow
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.dayChangeTicker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate

data class ConfirmRow(val plant: Plant, val ticked: Boolean)

sealed interface PlanVisitUiState {
    data object Loading : PlanVisitUiState

    /** No trip in progress or coming up, so there is nothing to plan. */
    data object NoTrip : PlanVisitUiState

    /** The trip's latest visit is still planned: tick what the sitter watered. */
    data class Confirm(val visit: SitterVisit, val rows: List<ConfirmRow>) : PlanVisitUiState

    data class Plan(
        val today: LocalDate,
        val trip: AwayPeriod,
        val earliestDay: LocalDate,
        val latestDay: LocalDate,
        val visitDay: LocalDate,
        val gapDays: Int,
        val projection: VisitProjection,
        val summary: VisitPlanSummary,
        val sharing: Boolean
    ) : PlanVisitUiState {
        val todayAllowed: Boolean get() = today in earliestDay..latestDay
        val tomorrowAllowed: Boolean get() = today.plusDays(1) in earliestDay..latestDay
    }
}

sealed interface PlanVisitEvent {
    /** [skipped] plants already had a watering on the visit day (the same-day duplicate guard). */
    data class Confirmed(val logged: Int, val skipped: Int) : PlanVisitEvent
    data object DidNotHappen : PlanVisitEvent
    data class SharePdf(val file: File, val caption: String) : PlanVisitEvent
    data class ShareText(val text: String) : PlanVisitEvent
    data object ShareFailed : PlanVisitEvent
}

/** Turns a [SitterList] into what the sitter receives; the Android-backed one lives in `SitterShare.kt`. */
interface SitterListOutput {
    /** Writes the PDF into the cache and returns it; clears any stale one first. Blocking: call off the main thread. */
    fun writePdf(list: SitterList): File
    fun text(list: SitterList): String
    fun caption(list: SitterList): String
}

class PlanVisitDependencies(
    val repository: AwayPeriodRepository,
    val plantRepository: PlantRepository,
    val careLogRepository: CareLogRepository,
    val quickLogUseCase: QuickLogUseCase,
    val seasonalAmplitude: Flow<Double>,
    val output: SitterListOutput
)

/**
 * Plan a sitter visit (product ADR-0065): the confirm step for the previous visit, then the plan screen with
 * its pure projection ([VisitProjector]) and share. A visit is saved, with its plant list, only when the
 * owner shares it. Every log goes through [QuickLogUseCase] and nothing here writes a plant row.
 */
class PlanVisitViewModel(
    private val deps: PlanVisitDependencies,
    dayChange: Flow<LocalDate> = dayChangeTicker(),
    private val hemisphere: () -> Hemisphere = SeasonalWatering::currentHemisphere,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val pickedDay = MutableStateFlow<LocalDate?>(null)
    private val gapDays = MutableStateFlow<Int?>(null)
    private val unticked = MutableStateFlow<Set<Long>>(emptySet())
    private val confirmSkipped = MutableStateFlow(false)
    private val sharing = MutableStateFlow(false)
    private val mutationLock = Mutex()

    private val _events = MutableSharedFlow<PlanVisitEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<PlanVisitEvent> = _events.asSharedFlow()

    val uiState: StateFlow<PlanVisitUiState> = combine(
        combine(
            deps.repository.observeAllWithVisits(),
            deps.plantRepository.getAllPlants(),
            deps.careLogRepository.getAllLogs(),
            deps.seasonalAmplitude,
            dayChange
        ) { trips, plants, logs, amplitude, today -> PlanVisitData(trips, plants, logs, amplitude, today) },
        combine(pickedDay, gapDays, unticked, confirmSkipped, sharing, ::PlanVisitInput)
    ) { data, input -> planVisitState(data, input, hemisphere()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlanVisitUiState.Loading)

    fun pickDay(day: LocalDate) {
        pickedDay.value = day
    }

    fun setGapDays(days: Int) {
        gapDays.value = days.coerceIn(MIN_GAP_DAYS, MAX_GAP_DAYS)
    }

    fun toggleConfirmRow(plantId: Long) {
        unticked.update { if (plantId in it) it - plantId else it + plantId }
    }

    /** Back out of the checklist without answering: the visit stays planned and the next list assumes it was watered. */
    fun skipConfirm() {
        confirmSkipped.value = true
    }

    fun confirmVisit(): Job = viewModelScope.launch {
        mutationLock.withLock {
            val confirm = uiState.value as? PlanVisitUiState.Confirm ?: return@withLock
            val tickedIds = confirm.rows.filter { it.ticked }.mapTo(HashSet()) { it.plant.id }
            val result = deps.quickLogUseCase.logSitterVisitWaterings(
                confirm.rows.filter { it.ticked }.map { it.plant },
                confirm.visit.day
            )
            deps.repository.updateVisit(
                confirm.visit.copy(
                    state = SitterVisitState.CONFIRMED,
                    plants = confirm.visit.plants.map { it.copy(watered = it.plantId in tickedIds) }
                )
            )
            unticked.value = emptySet()
            _events.tryEmit(PlanVisitEvent.Confirmed(result.loggedCount, result.skippedCount))
        }
    }

    fun visitDidNotHappen(): Job = viewModelScope.launch {
        mutationLock.withLock {
            val confirm = uiState.value as? PlanVisitUiState.Confirm ?: return@withLock
            deps.repository.updateVisit(confirm.visit.copy(state = SitterVisitState.DID_NOT_HAPPEN))
            unticked.value = emptySet()
            _events.tryEmit(PlanVisitEvent.DidNotHappen)
        }
    }

    /** Saves the visit with its list, then hands the sitter's PDF (or text) to the share sheet. */
    fun share(asText: Boolean): Job = viewModelScope.launch {
        mutationLock.withLock {
            val plan = uiState.value as? PlanVisitUiState.Plan ?: return@withLock
            if (plan.visitDay !in plan.earliestDay..plan.latestDay || plan.projection.listed.isEmpty()) {
                return@withLock
            }
            sharing.value = true
            try {
                saveVisit(plan)
                val list = SitterListComposer.compose(plan.projection)
                _events.tryEmit(
                    if (asText) {
                        PlanVisitEvent.ShareText(deps.output.text(list))
                    } else {
                        PlanVisitEvent.SharePdf(
                            withContext(ioDispatcher) { deps.output.writePdf(list) },
                            deps.output.caption(list)
                        )
                    }
                )
            } catch (_: IOException) {
                _events.tryEmit(PlanVisitEvent.ShareFailed)
            } finally {
                sharing.value = false
            }
        }
    }

    /** A same-day planned visit has its list replaced; otherwise a new planned visit is added. */
    private suspend fun saveVisit(plan: PlanVisitUiState.Plan) {
        val trip = deps.repository.getAllWithVisits().firstOrNull { it.period.id == plan.trip.id } ?: return
        val plants = plan.projection.listed.map { SitterVisitPlant(it.plant.id) }
        val existing = trip.visits.firstOrNull { it.day == plan.visitDay && it.state == SitterVisitState.PLANNED }
        if (existing != null) {
            deps.repository.updateVisit(existing.copy(plants = plants))
        } else {
            deps.repository.addVisit(SitterVisit(awayPeriodId = trip.period.id, day = plan.visitDay, plants = plants))
        }
    }

    class Factory(
        private val application: Application,
        private val repository: AwayPeriodRepository,
        private val plantRepository: PlantRepository,
        private val careLogRepository: CareLogRepository,
        private val quickLogUseCase: QuickLogUseCase,
        private val dataStore: DataStore<Preferences>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PlanVisitViewModel(
            PlanVisitDependencies(
                repository,
                plantRepository,
                careLogRepository,
                quickLogUseCase,
                dataStore.seasonalAmplitudeFlow(),
                AndroidSitterListOutput(application)
            )
        ) as T
    }

    companion object {
        const val MIN_GAP_DAYS = 1
        const val MAX_GAP_DAYS = 14
        private const val STOP_TIMEOUT_MS = 5000L
        private const val EVENT_BUFFER = 8
    }
}
