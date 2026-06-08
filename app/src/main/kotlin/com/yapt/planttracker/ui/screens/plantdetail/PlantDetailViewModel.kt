package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.ui.components.TimeRange
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlantDetailViewModel(
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val plantId: Long
) : ViewModel() {

    val plant: StateFlow<Plant?> = plantRepository.getPlantById(plantId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val careLogs: StateFlow<List<CareLog>> = careLogRepository.getLogsForPlant(plantId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val plantPhotos: StateFlow<List<PlantPhoto>> =
        plantPhotoRepository.getPhotosForPlant(plantId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val galleryPhotos: StateFlow<List<GalleryPhoto>> = combine(
        plantPhotoRepository.getPhotosForPlant(plantId),
        careLogRepository.getPhotoLogsForPlant(plantId)
    ) { plantPhotos, careLogPhotos ->
        val fromPlant = plantPhotos.map { GalleryPhoto(uri = it.uri, timestamp = it.capturedAt) }
        val fromLogs = careLogPhotos.mapNotNull { log ->
            log.photoUri?.let { GalleryPhoto(uri = it, timestamp = log.loggedAt) }
        }
        (fromPlant + fromLogs).distinctBy { it.uri }.sortedByDescending { it.timestamp }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val careStatus: StateFlow<PlantCareStatus?> = combine(plant, careLogs) { p, logs ->
        p ?: return@combine null
        val lastWatering = logs.firstOrNull { it.careType == CareType.WATER }
        val lastFertilizing = logs.firstOrNull { it.careType == CareType.FERTILIZE }
        CareSchedule.computeStatus(
            plant = p,
            lastWateredAt = lastWatering?.loggedAt,
            lastFertilizedAt = lastFertilizing?.loggedAt,
            totalLogs = logs.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val suggestedWateringInterval = MutableStateFlow<Int?>(null)

    val selectedTimeRange = MutableStateFlow(TimeRange.TWELVE_MONTHS)

    val showSkipDialog = MutableStateFlow(false)

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events

    fun clearSuggestedInterval() {
        suggestedWateringInterval.value = null
    }

    fun setTimeRange(range: TimeRange) {
        selectedTimeRange.value = range
    }

    fun applySuggestedInterval(newInterval: Int) {
        viewModelScope.launch {
            plant.value?.let { p ->
                plantRepository.updatePlant(
                    p.copy(
                        wateringIntervalDays = newInterval,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            suggestedWateringInterval.value = null
            _events.emit(Event.IntervalUpdated)
        }
    }

    fun requestSkip() {
        showSkipDialog.value = true
    }

    fun dismissSkipDialog() {
        showSkipDialog.value = false
    }

    fun confirmSkip(days: Int) {
        viewModelScope.launch {
            showSkipDialog.value = false
            plant.value?.let { p ->
                val currentDue = maxOf(
                    careStatus.value?.nextWateringDueAt ?: 0L,
                    System.currentTimeMillis()
                )
                val newOverride = currentDue + TimeUnit.DAYS.toMillis(days.toLong())
                plantRepository.updatePlant(
                    p.copy(wateringDueDateOverride = newOverride, updatedAt = System.currentTimeMillis())
                )
                val proposed = (p.wateringIntervalDays ?: 0) + days
                _events.emit(Event.SkipConfirmed(days, proposed))
            }
        }
    }

    fun deleteLog(log: CareLog) {
        viewModelScope.launch { careLogRepository.deleteLog(log) }
    }

    fun deletePhoto(photoUri: String) {
        viewModelScope.launch {
            val log = careLogs.value.firstOrNull { it.photoUri == photoUri }
            if (log != null) {
                careLogRepository.updateLog(log.copy(photoUri = null))
            }
            val plantPhoto = plantPhotos.value.firstOrNull { it.uri == photoUri }
            if (plantPhoto != null) {
                plantPhotoRepository.deletePhoto(plantPhoto)
            }
            plant.value?.let { p ->
                if (p.coverPhotoUri == photoUri) {
                    val nextPhoto = galleryPhotos.value
                        .filter { it.uri != photoUri }
                        .maxByOrNull { it.timestamp }?.uri
                    plantRepository.updatePlant(
                        p.copy(coverPhotoUri = nextPhoto, updatedAt = System.currentTimeMillis())
                    )
                }
            }
        }
    }

    sealed class Event {
        object IntervalUpdated : Event()
        data class SkipConfirmed(val skippedDays: Int, val proposedInterval: Int) : Event()
    }

    class Factory(
        private val plantRepository: PlantRepository,
        private val careLogRepository: CareLogRepository,
        private val plantPhotoRepository: PlantPhotoRepository,
        private val plantId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlantDetailViewModel(plantRepository, careLogRepository, plantPhotoRepository, plantId) as T
    }
}
