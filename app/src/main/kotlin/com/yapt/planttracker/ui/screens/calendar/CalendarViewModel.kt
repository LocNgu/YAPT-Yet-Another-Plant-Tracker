package com.yapt.planttracker.ui.screens.calendar

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.PhotoReminderRequest
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.model.WateringReason
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeFlow
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.dayChangeTicker
import com.yapt.planttracker.util.toLocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@Suppress("LongParameterList")
class CalendarViewModel(
    private val application: Application,
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val dataStore: DataStore<Preferences>,
    private val quickLogUseCase: QuickLogUseCase,
    private val dayChangeFlow: Flow<LocalDate> = dayChangeTicker(),
    private val nowProvider: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val allPlants: StateFlow<List<Plant>> = plantRepository.getAllPlants()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * A single shared, hot source for "today" — [statusesForDay] (and, through it, both
     * [plantsWithStatus] and [plantsByDay]) reads this rather than each collecting [dayChangeFlow]
     * independently. Two independent cold collectors of the ticker can tick a moment apart in real
     * usage (each re-derives its own delay from its own clock read), which could otherwise show a
     * transient frame where one flow has already rolled over to the new day while another is still
     * on yesterday's date. Sharing one [today] value means every consumer can only ever see the
     * same date. Public (not just an internal combine input) so `CalendarScreen` can drive its own
     * "today" highlight/label from the same source instead of a `remember { LocalDate.now() }` that
     * would otherwise never advance for a Calendar screen left composed across midnight (#550 review
     * round 3) — never add a second collection of [dayChangeFlow] for the UI layer to read from.
     */
    val today: StateFlow<LocalDate> =
        dayChangeFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocalDate.now())

    /** The day a [statuses] list was computed for, travelling together as one value. */
    private data class StatusesForDay(val day: LocalDate, val statuses: List<PlantCareStatus>)

    /**
     * Combines the plant statuses with the day they were computed against, as a single emission —
     * [plantsWithStatus] and [plantsByDay] are both derived from this rather than each reading
     * [today] on their own. `plantsByDay` used to be `combine(plantsWithStatus, _visibleMonth,
     * today)`, which re-ran as soon as *either* `plantsWithStatus` or `today` changed — at midnight,
     * `today` ticks first, so that combine could momentarily fire with the *new* `today` paired with
     * the *old*, not-yet-rebuilt `plantsWithStatus` (still mid-flight through [buildStatus]'s
     * suspend calls), grouping stale due/overdue statuses under the new day (#550 review round 2).
     * Deriving both public flows from this one combined value means a day and its statuses can never
     * be observed out of step with each other.
     *
     * [today]'s own emitted value is deliberately **not** what ends up in [StatusesForDay.day] — it
     * only *triggers* this combine (`_` below). `now` is instead captured fresh, once, right at the
     * top of the lambda, before any of [buildStatus]'s suspend `careLogRepository` calls — those
     * calls take real time, and `CareSchedule.computeStatus()` (via [buildStatus]) separately reads
     * a live `System.currentTimeMillis()` by default for its own overdue/due math. If a rebuild
     * triggered by an *unrelated* upstream (a new care log, a seasonal-amplitude change) happened to
     * straddle real midnight, the ticker's `today` value could still read yesterday while that
     * default live clock read inside `computeStatus()` already reads today — pairing yesterday's day
     * key with statuses computed as of today (#550 review round 4). Capturing one `now` up front and
     * threading it into both `now.toLocalDate()` and `buildStatus(..., now)` makes the day and the
     * statuses agree by construction, the same way [ReminderWorker][com.yapt.planttracker.worker
     * .ReminderWorker]'s own `buildStatus(app, plant, now, …)` already threads a single captured
     * `now` through. Mirrors [com.yapt.planttracker.domain.usecase.QuickLogUseCase]'s injectable
     * `nowProvider` convention so a test can pin the instant independently of the injected ticker.
     */
    private val statusesForDay: StateFlow<StatusesForDay> = combine(
        allPlants,
        careLogRepository.logCount,
        dataStore.seasonalAmplitudeFlow(),
        today
    ) { plants, _, seasonalAmplitude, _ ->
        val now = nowProvider()
        val day = now.toLocalDate()
        val statusList = mutableListOf<PlantCareStatus>()
        for (plant in plants) {
            statusList.add(buildStatus(careLogRepository, plant, seasonalAmplitude, now))
        }
        StatusesForDay(day, statusList)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatusesForDay(LocalDate.now(), emptyList()))

    val plantsWithStatus: StateFlow<List<PlantCareStatus>> = statusesForDay
        .map { it.statuses }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _visibleMonth = MutableStateFlow(YearMonth.now())
    val visibleMonth: StateFlow<YearMonth> = _visibleMonth.asStateFlow()

    /**
     * Reads [statusesForDay] rather than [plantsWithStatus] + [today] separately, so the statuses
     * grouped here are always the ones actually computed for the `day` they're grouped under — see
     * [statusesForDay]'s doc for the stale-pairing bug this avoids (#550 review round 2).
     */
    val plantsByDay: StateFlow<Map<LocalDate, DayEntry>> = combine(
        statusesForDay,
        _visibleMonth
    ) { forDay, month ->
        computePlantsByDay(forDay.statuses, month, forDay.day)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _selectedDay = MutableStateFlow<LocalDate?>(null)
    val selectedDay: StateFlow<LocalDate?> = _selectedDay.asStateFlow()

    val selectedDayPlants: StateFlow<List<PlantDayInfo>> = combine(
        plantsByDay,
        _selectedDay
    ) { byDay, day ->
        day?.let { selected ->
            byDay[selected]?.let { it.plants + it.dormantPlants }
        } ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _quickLogEvent = MutableSharedFlow<String>()
    val quickLogEvent: SharedFlow<String> = _quickLogEvent.asSharedFlow()

    private val _quickWaterSuggestion = MutableSharedFlow<QuickWaterSuggestion>()
    val quickWaterSuggestion: SharedFlow<QuickWaterSuggestion> = _quickWaterSuggestion.asSharedFlow()

    private val _photoReminderRequest = MutableStateFlow<PhotoReminderRequest?>(null)
    val photoReminderRequest: StateFlow<PhotoReminderRequest?> = _photoReminderRequest.asStateFlow()

    fun setVisibleMonth(month: YearMonth) {
        _visibleMonth.value = month
    }

    fun selectDay(day: LocalDate?) {
        _selectedDay.value = day
    }

    fun quickLog(plantId: Long, careType: CareType) {
        if (careType == CareType.WATER) {
            quickWater(plantId, reason = null)
            return
        }
        viewModelScope.launch {
            val plant = plantsWithStatus.value
                .firstOrNull { it.plant.id == plantId }?.plant ?: return@launch
            val outcome = quickLogUseCase.quickLog(plant, careType)
            _quickLogEvent.emit(outcome.message)
            if (outcome.logged) maybeTriggerPhotoReminder(plant.id)
        }
    }

    /**
     * A same-day duplicate is a silent no-op with an "Already watered today" snackbar instead of
     * inserting a second log (#509).
     */
    fun quickWater(plantId: Long, reason: WateringReason?) {
        viewModelScope.launch {
            val plant = plantsWithStatus.value
                .firstOrNull { it.plant.id == plantId }?.plant ?: return@launch
            val outcome = quickLogUseCase.quickWaterWithReason(plant, reason)
            outcome.suggestion?.let { _quickWaterSuggestion.emit(it) }
            _quickLogEvent.emit(outcome.message)
            if (outcome.logged) maybeTriggerPhotoReminder(plant.id)
        }
    }

    fun quickLiquidFertilize(plantId: Long, reason: WateringReason?) {
        viewModelScope.launch {
            val plant = plantsWithStatus.value
                .firstOrNull { it.plant.id == plantId }?.plant ?: return@launch
            val outcome = quickLogUseCase.quickLiquidFertilizeWithReason(plant, reason)
            outcome.suggestion?.let { _quickWaterSuggestion.emit(it) }
            _quickLogEvent.emit(outcome.message)
            if (outcome.logged) maybeTriggerPhotoReminder(plant.id)
        }
    }

    /**
     * Mirrors [com.yapt.planttracker.ui.screens.plantlist.PlantListViewModel]'s equivalent method:
     * both delegate to the shared [QuickLogUseCase], which owns the once-per-session-per-plant
     * gating via the shared `PhotoReminderPolicy.shownThisSession` set.
     */
    private suspend fun maybeTriggerPhotoReminder(plantId: Long) {
        quickLogUseCase.maybeBuildPhotoReminderRequest(plantId)?.let { request ->
            _photoReminderRequest.value = request
        }
    }

    fun dismissPhotoReminder() {
        _photoReminderRequest.value = null
    }

    fun saveReminderPhoto(plantId: Long, uri: Uri) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            plantPhotoRepository.addPhoto(PlantPhoto(plantId = plantId, uri = uri.toString(), capturedAt = now))
            plantRepository.getPlantById(plantId).first()?.let { p ->
                plantRepository.updatePlant(p.copy(coverPhotoUri = uri.toString(), updatedAt = now))
            }
            _photoReminderRequest.value = null
        }
    }

    /**
     * Applying the product ADR-0006 suggestion dialog. [suggestedIntervalDays] is the interval that was
     * originally suggested (before any retyping) — still base-space. [newInterval] is effective-space
     * (#644) — `CalendarScreen`'s editable field is pre-filled from and submits
     * `QuickWaterSuggestion.suggestedIntervalEffective`, matching the dialog's "Suggested: N days"
     * sentence. Delegates to [QuickLogUseCase.applyWateringIntervalSuggestion] (#631) — the same choke
     * point [com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel.applySuggestedInterval]
     * uses — so the effect (including the #572 base dual-write and the #626/#644 effective-space
     * handling) is identical regardless of which screen the dialog was shown from, rather than each
     * screen carrying its own copy of the math. Calendar has no silent-apply/undo equivalent, so the
     * result is intentionally not surfaced further — the dialog itself is dismissed by the caller.
     */
    fun applySuggestedInterval(
        plantId: Long,
        suggestedIntervalDays: Int,
        newInterval: Int,
        suggestedBaseInterval: Double?
    ) {
        viewModelScope.launch {
            plantRepository.getPlantById(plantId).first()?.let { p ->
                quickLogUseCase.applyWateringIntervalSuggestion(
                    p,
                    suggestedIntervalDays,
                    newInterval,
                    suggestedBaseInterval
                )
            }
        }
    }

    /**
     * Dismissing the product ADR-0006 suggestion dialog without applying. Delegates to
     * [QuickLogUseCase.recordWateringSuggestionDismissal] (#674) — the same choke point
     * [com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel.dismissSuggestedInterval]
     * uses — so the confidence bump and the matching [com.yapt.planttracker.domain.model
     * .WateringAdjustmentTrigger.DIALOG_DISMISSAL] row are identical regardless of which screen the
     * dialog was shown from, rather than this screen carrying its own copy of the confidence-only
     * logic that never wrote the adjustment row.
     */
    fun dismissSuggestedInterval(plantId: Long) {
        viewModelScope.launch {
            plantRepository.getPlantById(plantId).first()?.let { p ->
                quickLogUseCase.recordWateringSuggestionDismissal(p)
            }
        }
    }

    class Factory(
        private val application: Application,
        private val plantRepository: PlantRepository,
        private val careLogRepository: CareLogRepository,
        private val plantPhotoRepository: PlantPhotoRepository,
        private val dataStore: DataStore<Preferences>,
        private val quickLogUseCase: QuickLogUseCase
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CalendarViewModel(
                application,
                plantRepository,
                careLogRepository,
                plantPhotoRepository,
                dataStore,
                quickLogUseCase
            ) as T
    }
}

private suspend fun buildStatus(
    careLogRepository: CareLogRepository,
    plant: Plant,
    seasonalAmplitude: Double,
    now: Long
): PlantCareStatus {
    val lastWatering = careLogRepository.getLastLogOfType(plant.id, CareType.WATER)
    val lastFertilizing = careLogRepository.getLastLogOfType(plant.id, CareType.FERTILIZE)
    val totalLogs = careLogRepository.getCareLogCount(plant.id)
    return CareSchedule.computeStatus(
        plant = plant,
        lastWateredAt = lastWatering?.loggedAt,
        lastFertilizedAt = lastFertilizing?.loggedAt,
        totalLogs = totalLogs,
        now = now,
        seasonalAmplitude = seasonalAmplitude
    )
}
