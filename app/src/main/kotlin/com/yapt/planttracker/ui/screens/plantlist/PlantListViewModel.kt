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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class PlantListViewModel(
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository
) : ViewModel() {

    val rooms: StateFlow<List<String>> = plantRepository.getAllRooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedRoom = MutableStateFlow<String?>(null)

    val plantsWithStatus: StateFlow<List<PlantCareStatus>> = combine(
        plantRepository.getAllPlants(),
        selectedRoom
    ) { plants, room ->
        val filtered = if (room == null) plants else plants.filter { it.room == room }
        val result = mutableListOf<PlantCareStatus>()
        for (plant in filtered) {
            result.add(buildStatus(plant))
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectRoom(room: String?) {
        selectedRoom.value = room
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
