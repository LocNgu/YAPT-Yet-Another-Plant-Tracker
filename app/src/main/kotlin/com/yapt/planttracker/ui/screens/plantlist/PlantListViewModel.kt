package com.yapt.planttracker.ui.screens.plantlist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.schedule.CareSchedule
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

private val DEFAULT_SORT = SortOrder(option = SortOption.ALPHABETICAL, direction = SortDirection.ASC)

class PlantListViewModel(
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    val rooms: StateFlow<List<String>> = plantRepository.getAllRooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    }

    val plantsWithStatus: StateFlow<List<PlantCareStatus>> = combine(
        plantRepository.getAllPlants(),
        careLogRepository.logCount,
        selectedRoom,
        _sortOrder
    ) { plants, _, room, sort ->
        val filtered = if (room == null) plants else plants.filter { it.room == room }
        val statusList = mutableListOf<PlantCareStatus>()
        for (plant in filtered) {
            statusList.add(buildStatus(plant))
        }
        applySortOrder(statusList, sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _quickLogEvent = MutableSharedFlow<String>()
    val quickLogEvent: SharedFlow<String> = _quickLogEvent.asSharedFlow()

    fun quickLog(plantId: Long, careType: CareType) {
        viewModelScope.launch {
            val plantName = plantsWithStatus.value
                .firstOrNull { it.plant.id == plantId }
                ?.plant?.name ?: return@launch
            val log = CareLog(
                plantId = plantId,
                careType = careType,
                wateringFeedback = if (careType == CareType.WATER) WateringFeedback.JUST_RIGHT else null
            )
            careLogRepository.addLog(log)
            val message = when (careType) {
                CareType.WATER -> "Watered $plantName"
                CareType.FERTILIZE -> "Fertilized $plantName"
                else -> "${careType.displayName} $plantName"
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
                SortOption.RECENTLY_ADDED -> current
                else -> current.copy(
                    direction = if (current.direction == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                )
            }
        } else {
            val defaultDirection = when (option) {
                SortOption.ALPHABETICAL -> SortDirection.ASC
                SortOption.WATERING_DUE, SortOption.FERTILIZING_DUE -> SortDirection.DESC
                SortOption.RECENTLY_ADDED -> SortDirection.DESC
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
        private val plantRepository: PlantRepository,
        private val careLogRepository: CareLogRepository,
        private val dataStore: DataStore<Preferences>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlantListViewModel(plantRepository, careLogRepository, dataStore) as T
    }
}
