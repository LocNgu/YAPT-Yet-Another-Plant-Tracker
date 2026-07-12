package com.yapt.planttracker.ui.screens.calendar

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.R
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel
import com.yapt.planttracker.ui.screens.plantlist.PhotoReminderRequest
import com.yapt.planttracker.ui.screens.plantlist.QuickWaterSuggestion
import com.yapt.planttracker.ui.util.labelRes
import java.time.LocalDate
import java.time.YearMonth
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

class CalendarViewModel(
    private val application: Application,
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    private val allPlants: StateFlow<List<Plant>> = plantRepository.getAllPlants()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val plantsWithStatus: StateFlow<List<PlantCareStatus>> = combine(
        allPlants,
        careLogRepository.logCount
    ) { plants, _ ->
        val statusList = mutableListOf<PlantCareStatus>()
        for (plant in plants) {
            statusList.add(buildStatus(plant))
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
            quickWaterWithFeedback(plantId, WateringFeedback.JUST_RIGHT)
            return
        }
        viewModelScope.launch {
            val plant = plantsWithStatus.value
                .firstOrNull { it.plant.id == plantId }?.plant ?: return@launch
            val plantName = plant.name
            val now = System.currentTimeMillis()
            val log = CareLog(
                plantId = plantId,
                careType = careType,
                loggedAt = now,
                wateringFeedback = null,
                fertilizerType = if (careType == CareType.FERTILIZE && plant.useLiquidFertilizer) FertilizerType.LIQUID else FertilizerType.UNSPECIFIED
            )
            careLogRepository.addLog(log)
            if (careType == CareType.FERTILIZE && plant.useLiquidFertilizer) {
                careLogRepository.addLog(
                    CareLog(
                        plantId = plantId,
                        careType = CareType.WATER,
                        loggedAt = now,
                        wateringFeedback = WateringFeedback.JUST_RIGHT
                    )
                )
                plantRepository.getPlantById(plantId).first()?.let { p ->
                    if (p.wateringDueDateOverride != null)
                        plantRepository.updatePlant(
                            p.copy(wateringDueDateOverride = null, updatedAt = System.currentTimeMillis())
                        )
                }
            }
            val message = when (careType) {
                CareType.FERTILIZE -> if (plant.useLiquidFertilizer) {
                    application.getString(R.string.quick_log_watered_and_fertilized, plantName)
                } else {
                    application.getString(R.string.quick_log_fertilized, plantName)
                }
                else -> application.getString(R.string.quick_log_other, application.getString(careType.labelRes()), plantName)
            }
            _quickLogEvent.emit(message)
            maybeTriggerPhotoReminder(plant)
        }
    }

    fun quickWaterWithFeedback(plantId: Long, feedback: WateringFeedback) {
        viewModelScope.launch {
            val plant = plantsWithStatus.value
                .firstOrNull { it.plant.id == plantId }?.plant ?: return@launch
            val plantName = plant.name
            val now = System.currentTimeMillis()
            val log = CareLog(
                plantId = plantId,
                careType = CareType.WATER,
                loggedAt = now,
                wateringFeedback = feedback
            )
            careLogRepository.addLog(log)
            plantRepository.getPlantById(plantId).first()?.let { p ->
                if (p.wateringDueDateOverride != null)
                    plantRepository.updatePlant(
                        p.copy(wateringDueDateOverride = null, updatedAt = System.currentTimeMillis())
                    )
            }
            val lastTwo = careLogRepository.getLastTwoWaterings(plantId)
            if (lastTwo.size >= 2) {
                val actual = CareSchedule.daysBetween(lastTwo[1].loggedAt, lastTwo[0].loggedAt)
                val current = plant.wateringIntervalDays
                if (current != null && actual > 0) {
                    val suggestion = CareSchedule.computeSuggestedInterval(feedback, actual, current)
                    if (suggestion != current) {
                        _quickWaterSuggestion.emit(QuickWaterSuggestion(plantId, plantName, suggestion))
                    }
                }
            }
            _quickLogEvent.emit(application.getString(R.string.quick_log_watered, plantName))
            maybeTriggerPhotoReminder(plant)
        }
    }

    fun quickLiquidFertilizeWithFeedback(plantId: Long, feedback: WateringFeedback) {
        viewModelScope.launch {
            val plant = plantsWithStatus.value
                .firstOrNull { it.plant.id == plantId }?.plant ?: return@launch
            val plantName = plant.name
            val now = System.currentTimeMillis()
            careLogRepository.addLog(
                CareLog(
                    plantId = plantId,
                    careType = CareType.FERTILIZE,
                    loggedAt = now,
                    wateringFeedback = null,
                    fertilizerType = FertilizerType.LIQUID
                )
            )
            careLogRepository.addLog(
                CareLog(
                    plantId = plantId,
                    careType = CareType.WATER,
                    loggedAt = now,
                    wateringFeedback = feedback
                )
            )
            plantRepository.getPlantById(plantId).first()?.let { p ->
                if (p.wateringDueDateOverride != null)
                    plantRepository.updatePlant(
                        p.copy(wateringDueDateOverride = null, updatedAt = System.currentTimeMillis())
                    )
            }
            val lastTwo = careLogRepository.getLastTwoWaterings(plantId)
            if (lastTwo.size >= 2) {
                val actual = CareSchedule.daysBetween(lastTwo[1].loggedAt, lastTwo[0].loggedAt)
                val current = plant.wateringIntervalDays
                if (current != null && actual > 0) {
                    val suggestion = CareSchedule.computeSuggestedInterval(feedback, actual, current)
                    if (suggestion != current) {
                        _quickWaterSuggestion.emit(QuickWaterSuggestion(plantId, plantName, suggestion))
                    }
                }
            }
            _quickLogEvent.emit(application.getString(R.string.quick_log_watered_and_fertilized, plantName))
            maybeTriggerPhotoReminder(plant)
        }
    }

    /**
     * Mirrors [com.yapt.planttracker.ui.screens.plantlist.PlantListViewModel.maybeTriggerPhotoReminder]:
     * same once-per-session-per-plant semantics via the shared [PlantDetailViewModel.shownThisSession]
     * set, so a reminder already shown on Plants or Plant Detail this session is not repeated here.
     */
    private suspend fun maybeTriggerPhotoReminder(plant: Plant) {
        val enabled = dataStore.data.first()[SettingsKeys.PHOTO_REMINDER_ENABLED] ?: false
        if (!enabled) return
        if (plant.id in PlantDetailViewModel.shownThisSession) return
        val lastPlantPhotoTs = plantPhotoRepository.getPhotosForPlantOnce(plant.id)
            .maxOfOrNull { it.capturedAt }
        val lastCareLogPhotoTs = careLogRepository.getPhotoLogsForPlant(plant.id).first()
            .mapNotNull { log -> log.photoUri?.let { log.loggedAt } }
            .maxOrNull()
        val lastPhotoTs = listOfNotNull(lastPlantPhotoTs, lastCareLogPhotoTs).maxOrNull()
        val daysSince = PlantDetailViewModel.lastPhotoDaysSince(lastPhotoTs, plant.createdAt)
        if (daysSince >= PlantDetailViewModel.PHOTO_REMINDER_INTERVAL_DAYS) {
            PlantDetailViewModel.shownThisSession.add(plant.id)
            _photoReminderRequest.value = PhotoReminderRequest(plant.id, plant.name, daysSince)
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

    fun applySuggestedInterval(plantId: Long, newInterval: Int) {
        viewModelScope.launch {
            plantRepository.getPlantById(plantId).first()?.let { p ->
                plantRepository.updatePlant(
                    p.copy(wateringIntervalDays = newInterval, updatedAt = System.currentTimeMillis())
                )
            }
        }
    }

    private suspend fun buildStatus(plant: Plant): PlantCareStatus {
        val lastWatering = careLogRepository.getLastLogOfType(plant.id, CareType.WATER)
        val lastFertilizing = careLogRepository.getLastLogOfType(plant.id, CareType.FERTILIZE)
        val totalLogs = careLogRepository.getCareLogCount(plant.id)
        return CareSchedule.computeStatus(
            plant = plant,
            lastWateredAt = lastWatering?.loggedAt,
            lastFertilizedAt = lastFertilizing?.loggedAt,
            totalLogs = totalLogs
        )
    }

    class Factory(
        private val application: Application,
        private val plantRepository: PlantRepository,
        private val careLogRepository: CareLogRepository,
        private val plantPhotoRepository: PlantPhotoRepository,
        private val dataStore: DataStore<Preferences>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CalendarViewModel(application, plantRepository, careLogRepository, plantPhotoRepository, dataStore) as T
    }
}
