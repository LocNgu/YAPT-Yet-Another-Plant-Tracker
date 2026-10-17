package com.yapt.planttracker.ui.screens.away

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.away.AwayPeriods
import com.yapt.planttracker.domain.away.BeforeYouLeave
import com.yapt.planttracker.domain.away.BeforeYouLeaveInput
import com.yapt.planttracker.domain.away.WateringDue
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeFlow
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.dayChangeTicker
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
import java.time.LocalDate

data class BeforeYouLeaveRow(val plant: Plant, val dueDate: LocalDate, val ticked: Boolean)

sealed interface BeforeYouLeaveUiState {
    data object Loading : BeforeYouLeaveUiState

    /** Before you leave is offered on the last day at home only. */
    data object NotAvailable : BeforeYouLeaveUiState

    data class Ready(
        val today: LocalDate,
        val firstVisit: LocalDate,
        val waterToday: List<BeforeYouLeaveRow>,
        val checkTheSoil: List<BeforeYouLeaveRow>,
        val canWaitCount: Int
    ) : BeforeYouLeaveUiState {
        val tickedPlants: List<Plant>
            get() = (waterToday + checkTheSoil).filter { it.ticked }.map { it.plant }
    }
}

sealed interface BeforeYouLeaveEvent {
    /** [skipped] plants already had a watering today (the same-day duplicate guard). */
    data class Logged(val logged: Int, val skipped: Int) : BeforeYouLeaveEvent
}

/**
 * Before you leave (product ADR-0065): on the last day at home, the plants due strictly before halfway to
 * the first visit (ticked) and those due from halfway up to it (unticked), from [BeforeYouLeave.plan]. One
 * tap logs the ticked ones through [QuickLogUseCase.bulkLog], Plant List's bulk path: no reason prompt, and
 * the duplicate-day guard applies. The logs carry no feedback, so they stay out of learning exactly as a
 * "Just my timing" answer does.
 */
@Suppress("LongParameterList")
class BeforeYouLeaveViewModel(
    repository: AwayPeriodRepository,
    plantRepository: PlantRepository,
    careLogRepository: CareLogRepository,
    private val quickLogUseCase: QuickLogUseCase,
    seasonalAmplitude: Flow<Double>,
    dayChange: Flow<LocalDate> = dayChangeTicker(),
    hemisphere: () -> Hemisphere = SeasonalWatering::currentHemisphere
) : ViewModel() {

    private data class Inputs(
        val trips: List<com.yapt.planttracker.domain.model.AwayPeriod>,
        val plants: List<Plant>,
        val logs: List<CareLog>,
        val amplitude: Double,
        val today: LocalDate
    )

    private val ticks = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    private val _events = MutableSharedFlow<BeforeYouLeaveEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<BeforeYouLeaveEvent> = _events.asSharedFlow()
    private val logLock = Mutex()

    val uiState: StateFlow<BeforeYouLeaveUiState> = combine(
        combine(
            repository.observeAll(),
            plantRepository.getAllPlants(),
            careLogRepository.getAllLogs(),
            seasonalAmplitude,
            dayChange
        ) { trips, plants, logs, amplitude, today -> Inputs(trips, plants, logs, amplitude, today) },
        ticks
    ) { inputs, overrides -> stateOf(inputs, overrides, hemisphere()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BeforeYouLeaveUiState.Loading)

    fun toggle(plantId: Long) {
        val ready = uiState.value as? BeforeYouLeaveUiState.Ready ?: return
        val current = (ready.waterToday + ready.checkTheSoil).firstOrNull { it.plant.id == plantId } ?: return
        ticks.update { it + (plantId to !current.ticked) }
    }

    /** Logs today's watering for the ticked plants in one bulk call. */
    fun logTicked(): Job = viewModelScope.launch {
        logLock.withLock {
            val ready = uiState.value as? BeforeYouLeaveUiState.Ready ?: return@withLock
            val plants = ready.tickedPlants
            if (plants.isEmpty()) return@withLock
            val result = quickLogUseCase.bulkLog(plants, CareType.WATER)
            ticks.value = emptyMap()
            _events.tryEmit(BeforeYouLeaveEvent.Logged(result.loggedCount, result.skippedCount))
        }
    }

    private fun stateOf(
        inputs: Inputs,
        overrides: Map<Long, Boolean>,
        hemisphere: Hemisphere
    ): BeforeYouLeaveUiState {
        val trip = AwayPeriods.currentOrUpcoming(inputs.trips, inputs.today)
        if (trip == null || trip.firstDay != inputs.today.plusDays(1)) return BeforeYouLeaveUiState.NotAvailable
        val plan = BeforeYouLeave.plan(
            BeforeYouLeaveInput(
                plants = inputs.plants,
                careLogs = inputs.logs,
                today = inputs.today,
                visitEveryDays = trip.visitEveryDays,
                seasonalAmplitude = inputs.amplitude,
                hemisphere = hemisphere
            )
        )
        fun rows(dues: List<WateringDue>, default: Boolean) =
            dues.map { BeforeYouLeaveRow(it.plant, it.dueDate, overrides[it.plant.id] ?: default) }
        return BeforeYouLeaveUiState.Ready(
            today = inputs.today,
            firstVisit = plan.firstVisit,
            waterToday = rows(plan.waterToday, default = true),
            checkTheSoil = rows(plan.checkTheSoil, default = false),
            canWaitCount = plan.canWaitCount
        )
    }

    class Factory(
        private val repository: AwayPeriodRepository,
        private val plantRepository: PlantRepository,
        private val careLogRepository: CareLogRepository,
        private val quickLogUseCase: QuickLogUseCase,
        private val dataStore: DataStore<Preferences>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BeforeYouLeaveViewModel(
            repository,
            plantRepository,
            careLogRepository,
            quickLogUseCase,
            dataStore.seasonalAmplitudeFlow()
        ) as T
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5000L
    }
}
