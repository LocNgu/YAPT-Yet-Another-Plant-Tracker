package com.yapt.planttracker.ui.screens.plantlist

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.R
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.ui.util.labelRes
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

data class QuickWaterResult(val plantId: Long, val snackbarMessage: String, val suggestedInterval: Int?)

private val DEFAULT_SORT = SortOrder(option = SortOption.ALPHABETICAL, direction = SortDirection.ASC)

class PlantListViewModel(
    private val application: Application,
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val dataStore: DataStore<Preferences>
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
        applySortOrder(statusList, sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _quickLogEvent = MutableSharedFlow<String>()
    val quickLogEvent: SharedFlow<String> = _quickLogEvent.asSharedFlow()

    /** Emitted after a quick-water-with-feedback log completes. */
    private val _quickWaterResult = MutableSharedFlow<QuickWaterResult>()
    val quickWaterResult: SharedFlow<QuickWaterResult> = _quickWaterResult.asSharedFlow()

    /**
     * Logs a watering with the given [feedback] and emits a [QuickWaterResult] containing
     * the plant id, snackbar message, and (optionally) a suggested interval change.
     * Called from the quick-water bottom sheet on PlantCard.
     */
    fun quickLogWaterWithFeedback(plantId: Long, feedback: WateringFeedback) {
        viewModelScope.launch {
            val plant = plantsWithStatus.value
                .firstOrNull { it.plant.id == plantId }?.plant ?: return@launch
            val now = System.currentTimeMillis()
            val log = CareLog(
                plantId = plantId,
                careType = CareType.WATER,
                loggedAt = now,
                wateringFeedback = feedback
            )
            careLogRepository.addLog(log)
            // Clear any active skip override
            plantRepository.getPlantById(plantId).first()?.let { p ->
                if (p.wateringDueDateOverride != null)
                    plantRepository.updatePlant(
                        p.copy(wateringDueDateOverride = null, updatedAt = System.currentTimeMillis())
                    )
            }
            val suggestedInterval = computeSuggestedWateringInterval(plantId, feedback)
            val message = application.getString(R.string.quick_log_watered, plant.name)
            _quickWaterResult.emit(QuickWaterResult(plantId, message, suggestedInterval))
        }
    }

    private suspend fun computeSuggestedWateringInterval(plantId: Long, feedback: WateringFeedback): Int? {
        val plant = plantRepository.getPlantById(plantId).first() ?: return null
        val currentInterval = plant.wateringIntervalDays

        val lastTwoWaterings = careLogRepository.getLastTwoWaterings(plantId)
        val actualIntervalDays = if (lastTwoWaterings.size >= 2) {
            CareSchedule.daysBetween(lastTwoWaterings[1].loggedAt, lastTwoWaterings[0].loggedAt)
        } else {
            currentInterval ?: return null
        }
        if (actualIntervalDays <= 0) return null
        val suggested = CareSchedule.computeSuggestedInterval(feedback, actualIntervalDays, currentInterval)
        return if (suggested != currentInterval) suggested else null
    }

    fun quickLog(plantId: Long, careType: CareType) {
        viewModelScope.launch {
            val plant = plantsWithStatus.value
                .firstOrNull { it.plant.id == plantId }?.plant ?: return@launch
            val plantName = plant.name
            val now = System.currentTimeMillis()
            val log = CareLog(
                plantId = plantId,
                careType = careType,
                loggedAt = now,
                wateringFeedback = if (careType == CareType.WATER) WateringFeedback.JUST_RIGHT else null,
                fertilizerType = if (careType == CareType.FERTILIZE && plant.useLiquidFertilizer) FertilizerType.LIQUID else FertilizerType.UNSPECIFIED
            )
            careLogRepository.addLog(log)
            if (careType == CareType.WATER) {
                plantRepository.getPlantById(plantId).first()?.let { p ->
                    if (p.wateringDueDateOverride != null)
                        plantRepository.updatePlant(
                            p.copy(wateringDueDateOverride = null, updatedAt = System.currentTimeMillis())
                        )
                }
            }
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
                CareType.WATER -> application.getString(R.string.quick_log_watered, plantName)
                CareType.FERTILIZE -> if (plant.useLiquidFertilizer) {
                    application.getString(R.string.quick_log_watered_and_fertilized, plantName)
                } else {
                    application.getString(R.string.quick_log_fertilized, plantName)
                }
                else -> application.getString(R.string.quick_log_other, application.getString(careType.labelRes()), plantName)
            }
            _quickLogEvent.emit(message)
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

    private fun applySortOrder(list: List<PlantCareStatus>, sort: SortOrder): List<PlantCareStatus> {
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
        private val dataStore: DataStore<Preferences>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlantListViewModel(application, plantRepository, careLogRepository, dataStore) as T
    }
}
