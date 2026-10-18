package com.yapt.planttracker.ui.screens.hidden

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

class HiddenPlantsViewModel(
    private val plantRepository: PlantRepository
) : ViewModel() {

    val hiddenPlants: StateFlow<List<Plant>> = plantRepository.getHiddenPlants()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun unhidePlant(plantId: Long) {
        viewModelScope.launch {
            plantRepository.unhidePlant(plantId)
            _events.emit(Event.Unhidden)
        }
    }

    sealed class Event {
        object Unhidden : Event()
    }

    class Factory(
        private val plantRepository: PlantRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HiddenPlantsViewModel(plantRepository) as T
    }
}
