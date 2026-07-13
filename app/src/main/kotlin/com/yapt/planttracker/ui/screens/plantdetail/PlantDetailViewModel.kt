package com.yapt.planttracker.ui.screens.plantdetail

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.model.GalleryPhotoSource
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.ui.components.TimeRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlantDetailViewModel(
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val plantId: Long,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    val plant: StateFlow<Plant?> = plantRepository.getPlantById(plantId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val careLogs: StateFlow<List<CareLog>> = careLogRepository.getLogsForPlant(plantId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val plantPhotos: StateFlow<List<PlantPhoto>> =
        plantPhotoRepository.getPhotosForPlant(plantId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val galleryPhotos: StateFlow<List<GalleryPhoto>> = combine(
        plantPhotos,
        careLogRepository.getPhotoLogsForPlant(plantId)
    ) { plantPhotos, careLogPhotos ->
        val fromPlant = plantPhotos.map {
            GalleryPhoto(uri = it.uri, timestamp = it.capturedAt, source = GalleryPhotoSource.FromPlant(it))
        }
        val fromLogs = careLogPhotos.mapNotNull { log ->
            log.photoUri?.let {
                GalleryPhoto(uri = it, timestamp = log.loggedAt, source = GalleryPhotoSource.FromCareLog(log.id))
            }
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

    private val photoReminderEnabled: StateFlow<Boolean> = dataStore.data
        .map { it[SettingsKeys.PHOTO_REMINDER_ENABLED] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _showPhotoReminderDialog = MutableStateFlow(false)
    val showPhotoReminderDialog: StateFlow<Boolean> = _showPhotoReminderDialog.asStateFlow()

    private val _photoReminderDaysSince = MutableStateFlow(0L)
    val photoReminderDaysSince: StateFlow<Long> = _photoReminderDaysSince.asStateFlow()

    init {
        viewModelScope.launch {
            // drop(1) skips the stateIn seed (emptyList) and waits for the first real DB result,
            // preventing a false-positive reminder on plants that have recent photos.
            combine(plant, galleryPhotos.drop(1), photoReminderEnabled) { p: Plant?, photos: List<GalleryPhoto>, enabled: Boolean ->
                if (!enabled || p == null) return@combine
                if (p.id in shownThisSession) return@combine
                val lastPhotoTs = photos.firstOrNull()?.timestamp
                val daysSince = lastPhotoDaysSince(lastPhotoTs, p.createdAt)
                if (daysSince >= PHOTO_REMINDER_INTERVAL_DAYS) {
                    shownThisSession.add(p.id)
                    _photoReminderDaysSince.value = daysSince
                    _showPhotoReminderDialog.value = true
                }
            }.collect {}
        }
    }

    fun dismissPhotoReminder() {
        _showPhotoReminderDialog.value = false
    }

    fun saveReminderPhoto(uri: Uri) {
        viewModelScope.launch {
            val p = plant.value ?: return@launch
            val now = System.currentTimeMillis()
            plantPhotoRepository.addPhoto(PlantPhoto(plantId = p.id, uri = uri.toString(), capturedAt = now))
            careLogRepository.addLog(
                CareLog(
                    plantId = p.id,
                    careType = CareType.PHOTO,
                    loggedAt = now,
                    photoUri = uri.toString()
                )
            )
            plantRepository.updatePlant(p.copy(coverPhotoUri = uri.toString(), updatedAt = now))
        }
    }

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

    fun deletePhoto(photo: GalleryPhoto) {
        viewModelScope.launch {
            when (val src = photo.source) {
                is GalleryPhotoSource.FromPlant -> {
                    plantPhotoRepository.deletePhoto(src.photo)
                    val currentPlant = plant.value ?: return@launch
                    if (photo.uri == currentPlant.coverPhotoUri) {
                        val nextCover = plantPhotoRepository.getPhotosForPlantOnce(plantId).firstOrNull()
                        plantRepository.updatePlant(
                            currentPlant.copy(coverPhotoUri = nextCover?.uri, updatedAt = System.currentTimeMillis())
                        )
                    }
                }
                is GalleryPhotoSource.FromCareLog -> {
                    val log = careLogRepository.getLogById(src.logId) ?: return@launch
                    careLogRepository.updateLog(log.copy(photoUri = null))
                }
            }
        }
    }

    fun deleteLog(log: CareLog) {
        viewModelScope.launch { careLogRepository.deleteLog(log) }
    }

    sealed class Event {
        object IntervalUpdated : Event()
        data class SkipConfirmed(val skippedDays: Int, val proposedInterval: Int) : Event()
    }

    companion object {
        internal val shownThisSession = mutableSetOf<Long>()
        const val PHOTO_REMINDER_INTERVAL_DAYS = 30L

        fun lastPhotoDaysSince(
            lastPhotoTimestampMs: Long?,
            plantCreatedAtMs: Long,
            nowDate: LocalDate = LocalDate.now()
        ): Long {
            val anchorMs = lastPhotoTimestampMs ?: plantCreatedAtMs
            val anchorDate = Instant.ofEpochMilli(anchorMs)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            return ChronoUnit.DAYS.between(anchorDate, nowDate)
        }

        fun shouldShowPhotoReminder(
            lastPhotoTimestampMs: Long?,
            plantCreatedAtMs: Long,
            nowDate: LocalDate = LocalDate.now()
        ): Boolean = lastPhotoDaysSince(lastPhotoTimestampMs, plantCreatedAtMs, nowDate) >= PHOTO_REMINDER_INTERVAL_DAYS
    }

    class Factory(
        private val plantRepository: PlantRepository,
        private val careLogRepository: CareLogRepository,
        private val plantPhotoRepository: PlantPhotoRepository,
        private val plantId: Long,
        private val dataStore: DataStore<Preferences>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlantDetailViewModel(plantRepository, careLogRepository, plantPhotoRepository, plantId, dataStore) as T
    }
}
