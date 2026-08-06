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

    /**
     * Repotting is scheduled in **months**, not days — nobody repots on a day-precise cadence, and
     * the sane floor is roughly a quarter (fast-growing young plants) with most plants on a 1–3 year
     * cycle. Persisted as `months * DAYS_PER_MONTH` in `Plant.repottingIntervalDays` so the schedule
     * logic and the DB schema stay day-based (see product ADR-0022).
     */
    var repottingIntervalMonths by mutableIntStateOf(DEFAULT_REPOTTING_MONTHS)
    var repottingIntervalEnabled by mutableStateOf(false)

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
                    plant.repottingIntervalDays?.let {
                        repottingIntervalMonths = daysToRepottingMonths(it)
                        repottingIntervalEnabled = true
                    }
                    useLiquidFertilizer = plant.useLiquidFertilizer
                }
            }
        }
    }

    fun addPhoto(uri: String) {
        if (pendingPhotos.none { it == uri }) {
            pendingPhotos.add(uri)
        }
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
                repottingIntervalDays = if (repottingIntervalEnabled) {
                    repottingIntervalMonths * DAYS_PER_MONTH
                } else {
                    null
                },
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
                savePendingPhotos(plantId, now)
                _events.emit(Event.Saved(plantId))
            } else {
                val newId = plantRepository.addPlant(plant)
                savePendingPhotos(newId, now)
                _events.emit(Event.Saved(newId))
            }
        }
    }

    fun deletePlant() {
        plantId ?: return
        viewModelScope.launch {
            plantRepository.getPlantById(plantId).first()?.let { plant ->
                plantRepository.archivePlant(plant.id)
                _events.emit(Event.ArchivedForUndo(plant.id, plant.name))
            }
        }
    }

    private suspend fun savePendingPhotos(plantId: Long, now: Long) {
        if (pendingPhotos.isEmpty()) return
        plantPhotoRepository.addPhotos(
            pendingPhotos.map { PlantPhoto(plantId = plantId, uri = it, capturedAt = now) }
        )
    }

    sealed class Event {
        data class Saved(val plantId: Long) : Event()
        data class ArchivedForUndo(val plantId: Long, val plantName: String) : Event()
        data class ValidationError(val message: String) : Event()
    }

    companion object {
        /** Whole-month approximation used to convert the repotting interval to/from stored days. */
        const val DAYS_PER_MONTH = 30

        /**
         * Stored days → the months shown on the slider, rounded to the nearest whole month and
         * clamped into the slider's range. Values written by this screen are always exact multiples
         * of [DAYS_PER_MONTH], but a restored backup (or a value from a future/other client) need
         * not be — rounding keeps 405 days reading as 14 months rather than truncating to 13.
         */
        fun daysToRepottingMonths(days: Int): Int =
            ((days + DAYS_PER_MONTH / 2) / DAYS_PER_MONTH)
                .coerceIn(MIN_REPOTTING_MONTHS, MAX_REPOTTING_MONTHS)

        /** Roughly a quarter — the shortest cadence that makes sense for a fast-growing young plant. */
        const val MIN_REPOTTING_MONTHS = 3
        const val MAX_REPOTTING_MONTHS = 36
        const val DEFAULT_REPOTTING_MONTHS = 12
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
