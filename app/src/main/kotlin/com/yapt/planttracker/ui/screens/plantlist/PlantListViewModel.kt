package com.yapt.planttracker.ui.screens.plantlist

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.model.PhotoReminderRequest
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.DateUtils
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

private val DEFAULT_SORT = SortOrder(option = SortOption.ALPHABETICAL, direction = SortDirection.ASC)

class PlantListViewModel(
    private val application: Application,
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val dataStore: DataStore<Preferences>,
    private val quickLogUseCase: QuickLogUseCase
) : ViewModel() {

    private val allPlants: StateFlow<List<Plant>> = plantRepository.getAllPlants()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rooms: StateFlow<List<String>> = plantRepository.getAllRooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasUnassignedPlants: StateFlow<Boolean> = allPlants
        .map { plants -> plants.any { it.room == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val selectedRoom = MutableStateFlow<String?>(null)

    private val _sortOrder = MutableStateFlow(DEFAULT_SORT)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.data.first().let { prefs ->
                val option = runCatching {
                    SortOption.valueOf(prefs[SettingsKeys.SORT_OPTION]!!)
                }.getOrNull() ?: DEFAULT_SORT.option
                val ascending = prefs[SettingsKeys.SORT_ASCENDING] ?: (DEFAULT_SORT.direction == SortDirection.ASC)
                _sortOrder.value = SortOrder(
                    option = option,
                    direction = if (ascending) SortDirection.ASC else SortDirection.DESC
                )
            }
        }
        viewModelScope.launch {
            hasUnassignedPlants.collect { hasUnassigned ->
                if (!hasUnassigned && selectedRoom.value == UNASSIGNED_ROOM) {
                    selectedRoom.value = null
                }
            }
        }
    }

    val plantsWithStatus: StateFlow<List<PlantCareStatus>> = combine(
        allPlants,
        careLogRepository.logCount,
        selectedRoom,
        _sortOrder
    ) { plants, _, room, sort ->
        val filtered = when (room) {
            null -> plants
            UNASSIGNED_ROOM -> plants.filter { it.room == null }
            else -> plants.filter { it.room == room }
        }
        val statusList = mutableListOf<PlantCareStatus>()
        for (plant in filtered) {
            statusList.add(buildStatus(plant))
        }
        val caredTodayAt = if (sort.option == SortOption.CARED_FOR_TODAY) {
            val (start, end) = DateUtils.todayRangeMillis()
            careLogRepository.getLastCareAtBetween(start, end)
        } else {
            emptyMap()
        }
        applySortOrder(statusList, sort, caredTodayAt)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val plantListItems: StateFlow<List<PlantListItem>> = combine(
        plantsWithStatus,
        _sortOrder
    ) { statuses, sort ->
        groupPlantsByDueDate(statuses, sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _quickLogEvent = MutableSharedFlow<String>()
    val quickLogEvent: SharedFlow<String> = _quickLogEvent.asSharedFlow()

    private val _quickWaterSuggestion = MutableSharedFlow<QuickWaterSuggestion>()
    val quickWaterSuggestion: SharedFlow<QuickWaterSuggestion> = _quickWaterSuggestion.asSharedFlow()

    private val _photoReminderRequest = MutableStateFlow<PhotoReminderRequest?>(null)
    val photoReminderRequest: StateFlow<PhotoReminderRequest?> = _photoReminderRequest.asStateFlow()

    data class ArchivedEvent(val plantId: Long, val plantName: String)

    private val _archivedEvent = MutableSharedFlow<ArchivedEvent>()
    val archivedEvent: SharedFlow<ArchivedEvent> = _archivedEvent.asSharedFlow()

    fun onPlantArchived(plantId: Long, plantName: String) {
        viewModelScope.launch { _archivedEvent.emit(ArchivedEvent(plantId, plantName)) }
    }

    fun undoArchive(plantId: Long) {
        viewModelScope.launch { plantRepository.restorePlant(plantId) }
    }

    fun quickLog(plantId: Long, careType: CareType) {
        if (careType == CareType.WATER) {
            quickWaterWithFeedback(plantId, WateringFeedback.JUST_RIGHT)
            return
        }
        viewModelScope.launch {
            val plant = plantsWithStatus.value
                .firstOrNull { it.plant.id == plantId }?.plant ?: return@launch
            val message = quickLogUseCase.quickLog(plant, careType)
            _quickLogEvent.emit(message)
            maybeTriggerPhotoReminder(plant.id)
        }
    }

    /**
     * Logs a watering with the given [feedback] (called from the quick-water bottom sheet),
     * clears any active skip override, emits a snackbar message, and emits a
     * [QuickWaterSuggestion] if the adaptive interval system produces a suggestion.
     */
    fun quickWaterWithFeedback(plantId: Long, feedback: WateringFeedback) {
        viewModelScope.launch {
            val plant = plantsWithStatus.value
                .firstOrNull { it.plant.id == plantId }?.plant ?: return@launch
            val suggestion = quickLogUseCase.quickWaterWithFeedback(plant, feedback)
            if (suggestion != null) {
                _quickWaterSuggestion.emit(suggestion)
            }
            _quickLogEvent.emit(application.getString(R.string.quick_log_watered, plant.name))
            maybeTriggerPhotoReminder(plant.id)
        }
    }

    fun quickLiquidFertilizeWithFeedback(plantId: Long, feedback: WateringFeedback) {
        viewModelScope.launch {
            val plant = plantsWithStatus.value
                .firstOrNull { it.plant.id == plantId }?.plant ?: return@launch
            val suggestion = quickLogUseCase.quickLiquidFertilizeWithFeedback(plant, feedback)
            if (suggestion != null) {
                _quickWaterSuggestion.emit(suggestion)
            }
            _quickLogEvent.emit(application.getString(R.string.quick_log_watered_and_fertilized, plant.name))
            maybeTriggerPhotoReminder(plant.id)
        }
    }

    /**
     * After a quick action, shows a photo reminder for [plantId] if [QuickLogUseCase] determines
     * one is due (feature enabled, not already shown this session, photo stale enough).
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
            careLogRepository.addLog(
                CareLog(
                    plantId = plantId,
                    careType = CareType.PHOTO,
                    loggedAt = now,
                    photoUri = uri.toString()
                )
            )
            plantRepository.getPlantById(plantId).first()?.let { p ->
                plantRepository.updatePlant(p.copy(coverPhotoUri = uri.toString(), updatedAt = now))
            }
            _photoReminderRequest.value = null
        }
    }

    fun applySuggestedIntervalFromList(plantId: Long, newInterval: Int) {
        viewModelScope.launch {
            plantRepository.getPlantById(plantId).first()?.let { p ->
                plantRepository.updatePlant(
                    p.copy(wateringIntervalDays = newInterval, updatedAt = System.currentTimeMillis())
                )
            }
        }
    }

    fun selectRoom(room: String?) {
        selectedRoom.value = room
    }

    fun toggleSort(option: SortOption) {
        val current = _sortOrder.value
        val newOrder = if (option == current.option) {
            when (option) {
                SortOption.RECENTLY_ADDED, SortOption.BOTH_DUE -> current
                else -> current.copy(
                    direction = if (current.direction == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                )
            }
        } else {
            val defaultDirection = when (option) {
                SortOption.ALPHABETICAL -> SortDirection.ASC
                SortOption.WATERING_DUE, SortOption.FERTILIZING_DUE -> SortDirection.DESC
                SortOption.RECENTLY_ADDED, SortOption.BOTH_DUE -> SortDirection.DESC
                SortOption.CARED_FOR_TODAY -> SortDirection.DESC
            }
            SortOrder(option = option, direction = defaultDirection)
        }
        _sortOrder.value = newOrder
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.SORT_OPTION] = newOrder.option.name
                prefs[SettingsKeys.SORT_ASCENDING] = newOrder.direction == SortDirection.ASC
            }
        }
    }

    private fun applySortOrder(
        list: List<PlantCareStatus>,
        sort: SortOrder,
        caredTodayAt: Map<Long, Long> = emptyMap()
    ): List<PlantCareStatus> {
        val tiebreak = compareByDescending<PlantCareStatus> { it.plant.id }
        return when (sort.option) {
            SortOption.ALPHABETICAL -> {
                val comparator = if (sort.direction == SortDirection.ASC) {
                    compareBy<PlantCareStatus> { it.plant.name.lowercase() }.then(tiebreak)
                } else {
                    compareByDescending<PlantCareStatus> { it.plant.name.lowercase() }.then(tiebreak)
                }
                list.sortedWith(comparator)
            }
            SortOption.WATERING_DUE -> {
                val nullsLast = Comparator<PlantCareStatus> { a, b ->
                    val aVal = a.nextWateringDueAt
                    val bVal = b.nextWateringDueAt
                    when {
                        aVal == null && bVal == null -> 0
                        aVal == null -> 1
                        bVal == null -> -1
                        sort.direction == SortDirection.DESC -> aVal.compareTo(bVal)
                        else -> bVal.compareTo(aVal)
                    }
                }
                list.sortedWith(nullsLast.then(tiebreak))
            }
            SortOption.FERTILIZING_DUE -> {
                val nullsLast = Comparator<PlantCareStatus> { a, b ->
                    val aVal = a.nextFertilizingDueAt
                    val bVal = b.nextFertilizingDueAt
                    when {
                        aVal == null && bVal == null -> 0
                        aVal == null -> 1
                        bVal == null -> -1
                        sort.direction == SortDirection.DESC -> aVal.compareTo(bVal)
                        else -> bVal.compareTo(aVal)
                    }
                }
                list.sortedWith(nullsLast.then(tiebreak))
            }
            SortOption.RECENTLY_ADDED -> {
                list.sortedWith(tiebreak)
            }
            SortOption.BOTH_DUE -> {
                val nullsLast = Comparator<PlantCareStatus> { a, b ->
                    val aVal = a.nextWateringDueAt
                    val bVal = b.nextWateringDueAt
                    when {
                        aVal == null && bVal == null -> 0
                        aVal == null -> 1
                        bVal == null -> -1
                        else -> aVal.compareTo(bVal)
                    }
                }
                list
                    .filter { s ->
                        (s.isOverdue || s.isDueSoon) && (s.isFertilizingOverdue || s.isFertilizingDueSoon)
                    }
                    .sortedWith(nullsLast.then(tiebreak))
            }
            SortOption.CARED_FOR_TODAY -> {
                // Keep only plants with ≥ 1 care log today; order by most-recent care-log
                // timestamp. DESC = most-recently-cared first, ASC = earliest-in-the-day first.
                val comparator = if (sort.direction == SortDirection.ASC) {
                    compareBy<PlantCareStatus> { caredTodayAt[it.plant.id] ?: 0L }.then(tiebreak)
                } else {
                    compareByDescending<PlantCareStatus> { caredTodayAt[it.plant.id] ?: 0L }.then(tiebreak)
                }
                list.filter { caredTodayAt.containsKey(it.plant.id) }.sortedWith(comparator)
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

    companion object {
        const val UNASSIGNED_ROOM = " unassigned"
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
            PlantListViewModel(application, plantRepository, careLogRepository, plantPhotoRepository, dataStore, quickLogUseCase) as T
    }
}
