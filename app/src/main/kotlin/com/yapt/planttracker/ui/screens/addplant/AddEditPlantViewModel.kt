package com.yapt.planttracker.ui.screens.addplant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantPhoto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AddEditPlantViewModel(
    private val plantRepository: PlantRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val plantId: Long?
) : ViewModel() {

    val isEditMode: Boolean = plantId != null

    var name by mutableStateOf("")
    var species by mutableStateOf("")
    var room by mutableStateOf("")
    var notes by mutableStateOf("")
    var coverPhotoUri by mutableStateOf<String?>(null)
    var wateringIntervalDays by mutableIntStateOf(7)
    var wateringIntervalEnabled by mutableStateOf(false)
    var fertilizingIntervalDays by mutableIntStateOf(30)
    var fertilizingIntervalEnabled by mutableStateOf(false)
    var useLiquidFertilizer by mutableStateOf(false)

    val pendingPhotos = mutableStateListOf<String>()

    val rooms: StateFlow<List<String>> = plantRepository.getAllRooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events

    init {
        plantId?.let { id ->
            viewModelScope.launch {
                plantRepository.getPlantById(id).first()?.let { plant ->
                    name = plant.name
                    species = plant.species ?: ""
                    room = plant.room ?: ""
                    notes = plant.notes ?: ""
                    coverPhotoUri = plant.coverPhotoUri
                    plant.wateringIntervalDays?.let {
                        wateringIntervalDays = it
                        wateringIntervalEnabled = true
                    }
                    plant.fertilizingIntervalDays?.let {
                        fertilizingIntervalDays = it
                        fertilizingIntervalEnabled = true
                    }
                    useLiquidFertilizer = plant.useLiquidFertilizer
                }
            }
        }
    }

    fun addPhoto(uri: String) {
        pendingPhotos.add(uri)
        coverPhotoUri = uri
    }

    fun save() {
        if (name.isBlank()) {
            viewModelScope.launch { _events.emit(Event.ValidationError("Plant name is required")) }
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val plant = Plant(
                id = plantId ?: 0,
                name = name.trim(),
                species = species.trim().ifBlank { null },
                room = room.trim().ifBlank { null },
                notes = notes.trim().ifBlank { null },
                coverPhotoUri = coverPhotoUri,
                wateringIntervalDays = if (wateringIntervalEnabled) wateringIntervalDays else null,
                fertilizingIntervalDays = if (fertilizingIntervalEnabled) fertilizingIntervalDays else null,
                createdAt = if (isEditMode) 0L else now,
                updatedAt = now,
                useLiquidFertilizer = useLiquidFertilizer
            )
            if (isEditMode) {
                val existing = plantRepository.getPlantById(plantId!!).first()
                plantRepository.updatePlant(
                    plant.copy(
                        createdAt = existing?.createdAt ?: now,
                        wateringDueDateOverride = existing?.wateringDueDateOverride
                    )
                )
                for (photoUri in pendingPhotos) {
                    plantPhotoRepository.addPhoto(PlantPhoto(plantId = plantId, uri = photoUri, capturedAt = now))
                }
                _events.emit(Event.Saved(plantId))
            } else {
                val newId = plantRepository.addPlant(plant)
                for (photoUri in pendingPhotos) {
                    plantPhotoRepository.addPhoto(PlantPhoto(plantId = newId, uri = photoUri, capturedAt = now))
                }
                _events.emit(Event.Saved(newId))
            }
        }
    }

    fun deletePlant() {
        plantId ?: return
        viewModelScope.launch {
            plantRepository.getPlantById(plantId).first()?.let {
                plantRepository.deletePlant(it)
                _events.emit(Event.Deleted)
            }
        }
    }

    sealed class Event {
        data class Saved(val plantId: Long) : Event()
        object Deleted : Event()
        data class ValidationError(val message: String) : Event()
    }

    class Factory(
        private val plantRepository: PlantRepository,
        private val plantPhotoRepository: PlantPhotoRepository,
        private val plantId: Long?
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AddEditPlantViewModel(plantRepository, plantPhotoRepository, plantId) as T
    }
}
