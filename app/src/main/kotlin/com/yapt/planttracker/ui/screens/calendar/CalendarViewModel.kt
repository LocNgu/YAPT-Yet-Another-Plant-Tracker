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
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.isFeatureEnabled
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

class CalendarViewModel(
    private val application: Application,
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val dataStore: DataStore<Preferences>,
    private val quickLogUseCase: QuickLogUseCase
) : ViewModel() {

    private val allPlants: StateFlow<List<Plant>> = plantRepository.getAllPlants()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val plantsWithStatus: StateFlow<List<PlantCareStatus>> = combine(
        allPlants,
        careLogRepository.logCount,
        dataStore.seasonalAmplitudeFlow()
    ) { plants, _, seasonalAmplitude ->
        val statusList = mutableListOf<PlantCareStatus>()
        for (plant in plants) {
            statusList.add(buildStatus(careLogRepository, plant, seasonalAmplitude))
        }
        statusList
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _visibleMonth = MutableStateFlow(YearMonth.now())
    val visibleMonth: StateFlow<YearMonth> = _visibleMonth.asStateFlow()

    val plantsByDay: StateFlow<Map<LocalDate, DayEntry>> = combine(
        plantsWithStatus,
        _visibleMonth
    ) { statuses, month ->
        computePlantsByDay(statuses, month, LocalDate.now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _selectedDay = MutableStateFlow<LocalDate?>(null)
    val selectedDay: StateFlow<LocalDate?> = _selectedDay.asStateFlow()

    val selectedDayPlants: StateFlow<List<PlantDayInfo>> = combine(
        plantsByDay,
        _selectedDay
    ) { byDay, day ->
        day?.let { byDay[it]?.plants } ?: emptyList()
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
     * Applying the ADR-0006 suggestion dialog. [suggestedIntervalDays] is the interval that was
     * originally suggested (before any retyping); the same confidence rules as
     * [com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel.applySuggestedInterval]
     * apply here so the effect is identical regardless of which screen the dialog was shown from
     * (#568 comment 5).
     */
    fun applySuggestedInterval(plantId: Long, suggestedIntervalDays: Int, newInterval: Int) {
        viewModelScope.launch {
            plantRepository.getPlantById(plantId).first()?.let { p ->
                val wateringConfidence = if (dataStore.isFeatureEnabled(FeatureFlagRegistry.ADAPTIVE_WATERING)) {
                    CareSchedule.confidenceAfterDialogEdit(p.wateringConfidence, suggestedIntervalDays, newInterval)
                } else {
                    p.wateringConfidence
                }
                plantRepository.updatePlant(
                    p.copy(
                        wateringIntervalDays = newInterval,
                        wateringConfidence = wateringConfidence,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /**
     * Dismissing the ADR-0006 suggestion dialog without applying — mirrors
     * [com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel.dismissSuggestedInterval]
     * so the confidence effect is the same regardless of which screen the dialog was shown from
     * (#568 comment 5).
     */
    fun dismissSuggestedInterval(plantId: Long) {
        viewModelScope.launch {
            if (dataStore.isFeatureEnabled(FeatureFlagRegistry.ADAPTIVE_WATERING)) {
                plantRepository.getPlantById(plantId).first()?.let { p ->
                    plantRepository.updatePlant(
                        p.copy(
                            wateringConfidence = CareSchedule.confidenceAfterDismissal(p.wateringConfidence),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
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
    seasonalAmplitude: Double
): PlantCareStatus {
    val lastWatering = careLogRepository.getLastLogOfType(plant.id, CareType.WATER)
    val lastFertilizing = careLogRepository.getLastLogOfType(plant.id, CareType.FERTILIZE)
    val totalLogs = careLogRepository.getCareLogCount(plant.id)
    return CareSchedule.computeStatus(
        plant = plant,
        lastWateredAt = lastWatering?.loggedAt,
        lastFertilizedAt = lastFertilizing?.loggedAt,
        totalLogs = totalLogs,
        seasonalAmplitude = seasonalAmplitude
    )
}
