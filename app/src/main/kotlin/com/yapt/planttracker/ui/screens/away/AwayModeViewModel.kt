package com.yapt.planttracker.ui.screens.away

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.domain.away.AwayPeriodValidation
import com.yapt.planttracker.domain.away.AwayPeriods
import com.yapt.planttracker.domain.away.AwayTripStatus
import com.yapt.planttracker.domain.away.awayTripStatus
import com.yapt.planttracker.domain.away.showsBanner
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.AwayPeriodWithVisits
import com.yapt.planttracker.util.dayChangeTicker
import com.yapt.planttracker.worker.ReminderScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

data class AwayModeUiState(val today: LocalDate, val periods: List<AwayPeriod>) {
    val status: AwayTripStatus = awayTripStatus(periods, today)
    val showsBanner: Boolean get() = status.showsBanner(today)

    /** The owner's last day at home: the trip is upcoming and starts tomorrow. */
    val isLastDayAtHome: Boolean get() = (status as? AwayTripStatus.Upcoming)?.trip?.firstDay == today.plusDays(1)
}

sealed interface AwayModeEvent {
    data class VisitsRemoved(val count: Int) : AwayModeEvent
    data class Rejected(val validation: AwayPeriodValidation) : AwayModeEvent
}

internal data class AwayModeActions(
    val onSave: (firstDay: LocalDate, lastDay: LocalDate, editingId: Long?) -> Unit,
    val onCancelTrip: () -> Unit,
    val onImBack: () -> Unit
)

/**
 * Backs Settings' Away mode section and the Care banner (product ADR-0065). Trips never touch a plant row.
 * Every mutation that changes whether *today* is an away day calls [onTodaysAwayStateChanged], so the
 * notification shade refreshes at once; the daily worker gate itself is stateless (technical ADR-0038).
 */
class AwayModeViewModel(
    private val repository: AwayPeriodRepository,
    private val onTodaysAwayStateChanged: suspend () -> Unit,
    dayChange: Flow<LocalDate> = dayChangeTicker(),
    private val todayProvider: () -> LocalDate = { LocalDate.now() }
) : ViewModel() {

    val uiState: StateFlow<AwayModeUiState?> = combine(repository.observeAll(), dayChange) { periods, today ->
        AwayModeUiState(today, periods)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _events = MutableSharedFlow<AwayModeEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<AwayModeEvent> = _events.asSharedFlow()

    private val mutationLock = Mutex()

    fun saveTrip(firstDay: LocalDate, lastDay: LocalDate, editingId: Long?): Job = mutate { today, trips ->
        val periods = trips.map { it.period }
        val validation = AwayPeriods.validate(firstDay, lastDay, today, periods, editingId)
        if (validation != AwayPeriodValidation.Valid) {
            _events.tryEmit(AwayModeEvent.Rejected(validation))
            return@mutate
        }
        val editing = editingId?.let { id -> trips.firstOrNull { it.period.id == id } }
        when {
            editingId == null -> repository.addPeriod(AwayPeriod(firstDay = firstDay, lastDay = lastDay))
            editing != null -> {
                val stranded = AwayPeriods.visitsOutside(editing.visits, firstDay, lastDay)
                repository.updatePeriodAndDeleteVisits(
                    editing.period.copy(firstDay = firstDay, lastDay = lastDay),
                    stranded.map { it.id }
                )
                reportRemovedVisits(stranded.size)
            }
        }
    }

    fun cancelTrip(): Job = mutate { today, trips ->
        val upcoming = trips.firstOrNull { it.period.id == currentId(trips, today) && it.period.firstDay > today }
            ?: return@mutate
        repository.deletePeriod(upcoming.period)
        reportRemovedVisits(upcoming.visits.size)
    }

    fun imBack(): Job = mutate { today, trips ->
        val trip = trips.firstOrNull { it.period.id == currentId(trips, today) && it.period.firstDay <= today }
            ?: return@mutate
        val shortened = AwayPeriods.endEarly(trip.period, today)
        if (shortened == null) {
            repository.deletePeriod(trip.period)
            reportRemovedVisits(trip.visits.size)
        } else {
            val stranded = AwayPeriods.visitsOutside(trip.visits, shortened.firstDay, shortened.lastDay)
            repository.updatePeriodAndDeleteVisits(shortened, stranded.map { it.id })
            reportRemovedVisits(stranded.size)
        }
    }

    private fun currentId(trips: List<AwayPeriodWithVisits>, today: LocalDate): Long? =
        AwayPeriods.currentOrUpcoming(trips.map { it.period }, today)?.id

    private fun reportRemovedVisits(count: Int) {
        if (count > 0) _events.tryEmit(AwayModeEvent.VisitsRemoved(count))
    }

    private fun mutate(block: suspend (LocalDate, List<AwayPeriodWithVisits>) -> Unit): Job = viewModelScope.launch {
        mutationLock.withLock {
            val today = todayProvider()
            val before = repository.getAllWithVisits()
            block(today, before)
            val after = repository.getAll()
            val wasAway = AwayPeriods.isAway(today, before.map { it.period })
            if (wasAway != AwayPeriods.isAway(today, after)) onTodaysAwayStateChanged()
        }
    }

    class Factory(
        private val application: Application,
        private val repository: AwayPeriodRepository,
        private val dataStore: DataStore<Preferences>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AwayModeViewModel(
            repository,
            onTodaysAwayStateChanged = {
                // The daily schedule is cancelled while reminders are off, and the worker checks only the
                // runtime permission, so an immediate run must respect the in-app switch itself.
                if (dataStore.data.first()[SettingsKeys.NOTIFICATIONS_ENABLED] ?: true) {
                    ReminderScheduler.runNow(application)
                }
            }
        ) as T
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5000L
        const val EVENT_BUFFER = 8
    }
}
