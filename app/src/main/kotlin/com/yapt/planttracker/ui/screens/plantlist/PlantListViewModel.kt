package com.yapt.planttracker.ui.screens.plantlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.schedule.CareSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class PlantListViewModel(
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository
) : ViewModel() {

    val rooms: StateFlow<List<String>> = plantRepository.getAllRooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedRoom = MutableStateFlow<String?>(null)

    private val _sortOrder = MutableStateFlow(SortOrder())
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    val plantsWithStatus: StateFlow<List<PlantCareStatus>> = combine(
        plantRepository.getAllPlants(),
        selectedRoom,
        _sortOrder
    ) { plants, room, sort ->
        val filtered = if (room == null) plants else plants.filter { it.room == room }
        val result = mutableListOf<PlantCareStatus>()
        for (plant in filtered) {
            result.add(buildStatus(plant))
        }
        applySortOrder(result, sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectRoom(room: String?) {
        selectedRoom.value = room
    }

    fun toggleSort(option: SortOption) {
        val current = _sortOrder.value
        _sortOrder.value = if (option == current.option) {
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
        val lastWatering = careLogRepository.getLastLogOfType(
            plant.id, com.yapt.planttracker.domain.model.CareType.WATER
        )
        val lastFertilizing = careLogRepository.getLastLogOfType(
            plant.id, com.yapt.planttracker.domain.model.CareType.FERTILIZE
        )
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
        private val careLogRepository: CareLogRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlantListViewModel(plantRepository, careLogRepository) as T
    }
}
