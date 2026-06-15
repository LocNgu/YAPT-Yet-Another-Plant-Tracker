package com.yapt.planttracker.ui.screens.graveyard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GraveyardViewModel(
    private val plantRepository: PlantRepository
) : ViewModel() {

    val archivedPlants: StateFlow<List<Plant>> = plantRepository.getArchivedPlants()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun restorePlant(plantId: Long) {
        viewModelScope.launch {
            plantRepository.restorePlant(plantId)
            _events.emit(Event.Restored)
        }
    }

    fun deletePermanently(plant: Plant) {
        viewModelScope.launch {
            plantRepository.deletePlant(plant)
            _events.emit(Event.Deleted)
        }
    }

    fun emptyGraveyard() {
        viewModelScope.launch {
            plantRepository.deleteAllArchived()
            _events.emit(Event.GraveyardEmptied)
        }
    }

    sealed class Event {
        object Restored : Event()
        object Deleted : Event()
        object GraveyardEmptied : Event()
    }

    class Factory(
        private val plantRepository: PlantRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GraveyardViewModel(plantRepository) as T
    }
}
