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
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.ui.components.TimeRange
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
import java.util.concurrent.TimeUnit

class PlantDetailViewModel(
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val plantId: Long,
    private val dataStore: DataStore<Preferences>,
    private val quickLogUseCase: QuickLogUseCase
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

    internal val selectedTimeRange = MutableStateFlow(TimeRange.TWELVE_MONTHS)

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

    private val _quickLogMessage = MutableSharedFlow<QuickLogMessage>()
    val quickLogMessage: SharedFlow<QuickLogMessage> = _quickLogMessage

    init {
        viewModelScope.launch {
            // drop(1) skips the stateIn seed (emptyList) and waits for the first real DB result,
            // preventing a false-positive reminder on plants that have recent photos.
            combine(
                plant,
                galleryPhotos.drop(1),
                photoReminderEnabled
            ) { p: Plant?, photos: List<GalleryPhoto>, enabled: Boolean ->
                if (!enabled || p == null) return@combine
                if (p.id in PhotoReminderPolicy.shownThisSession) return@combine
                val lastPhotoTs = photos.firstOrNull()?.timestamp
                val daysSince = PhotoReminderPolicy.lastPhotoDaysSince(lastPhotoTs, p.createdAt)
                if (daysSince >= PhotoReminderPolicy.PHOTO_REMINDER_INTERVAL_DAYS) {
                    PhotoReminderPolicy.shownThisSession.add(p.id)
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

    /**
     * Quick-logs a watering with [feedback] from the tappable watering stat chip. Reuses the shared
     * [QuickLogUseCase] so behaviour matches the PlantList/Calendar quick-water paths; any adaptive
     * interval suggestion feeds the existing interval-suggestion dialog via [suggestedWateringInterval].
     */
    fun quickWater(feedback: WateringFeedback) {
        viewModelScope.launch {
            val p = plant.value ?: return@launch
            quickLogUseCase.quickWaterWithFeedback(p, feedback)?.let {
                suggestedWateringInterval.value = it.suggestedInterval
            }
            _quickLogMessage.emit(QuickLogMessage.Watered(p.name))
            maybeTriggerPhotoReminder(p.id)
        }
    }

    /**
     * Quick-logs a fertilizing from the tappable fertilizing stat chip. The screen only routes
     * regular (non-liquid) plants here, but the snackbar is derived from the plant type so it stays
     * correct even if called for a liquid-fertilizer plant — `QuickLogUseCase.quickLog` already
     * inserts the paired WATER log in that case (ADR-0008/ADR-0017).
     */
    fun quickFertilize() {
        viewModelScope.launch {
            val p = plant.value ?: return@launch
            quickLogUseCase.quickLog(p, CareType.FERTILIZE)
            val message = if (p.useLiquidFertilizer) {
                QuickLogMessage.WateredAndFertilized(p.name)
            } else {
                QuickLogMessage.Fertilized(p.name)
            }
            _quickLogMessage.emit(message)
            maybeTriggerPhotoReminder(p.id)
        }
    }

    /**
     * Quick-logs a paired fertilize + watering for liquid-fertilizer plants from the fertilizing stat
     * chip, mirroring the combined water+fertilize path on PlantCard (ADR-0008/ADR-0017).
     */
    fun quickLiquidFertilize(feedback: WateringFeedback) {
        viewModelScope.launch {
            val p = plant.value ?: return@launch
            quickLogUseCase.quickLiquidFertilizeWithFeedback(p, feedback)?.let {
                suggestedWateringInterval.value = it.suggestedInterval
            }
            _quickLogMessage.emit(QuickLogMessage.WateredAndFertilized(p.name))
            maybeTriggerPhotoReminder(p.id)
        }
    }

    private suspend fun maybeTriggerPhotoReminder(plantId: Long) {
        quickLogUseCase.maybeBuildPhotoReminderRequest(plantId)?.let { request ->
            _photoReminderDaysSince.value = request.daysSince
            _showPhotoReminderDialog.value = true
        }
    }

    fun clearSuggestedInterval() {
        suggestedWateringInterval.value = null
    }

    internal fun setTimeRange(range: TimeRange) {
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

    /**
     * Inline scheduling edits from the Plant Detail tabs (#436, product ADR-0022). Each persists a
     * single field straight through [PlantRepository.updatePlant]; the change flows back via the
     * [plant] StateFlow so the tab insights update immediately. A `null` interval clears the schedule
     * (the "Not scheduled" state), matching the reminder toggle on Add/Edit Plant.
     */
    fun setWateringInterval(days: Int?) {
        viewModelScope.launch {
            plant.value?.let { p ->
                plantRepository.updatePlant(
                    p.copy(wateringIntervalDays = days, updatedAt = System.currentTimeMillis())
                )
            }
        }
    }

    fun setFertilizingInterval(days: Int?) {
        viewModelScope.launch {
            plant.value?.let { p ->
                plantRepository.updatePlant(
                    p.copy(fertilizingIntervalDays = days, updatedAt = System.currentTimeMillis())
                )
            }
        }
    }

    fun setLiquidFertilizer(enabled: Boolean) {
        viewModelScope.launch {
            plant.value?.let { p ->
                plantRepository.updatePlant(
                    p.copy(useLiquidFertilizer = enabled, updatedAt = System.currentTimeMillis())
                )
            }
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

    companion object {
        /** Interval a schedule starts at when the user enables it inline on a tab (mirrors Add/Edit). */
        const val DEFAULT_WATERING_INTERVAL_DAYS = 7
        const val DEFAULT_FERTILIZING_INTERVAL_DAYS = 30
    }

    sealed class Event {
        object IntervalUpdated : Event()
        data class SkipConfirmed(val skippedDays: Int, val proposedInterval: Int) : Event()
    }

    /** One-shot snackbar messages emitted after a quick-log from the tappable stat chips. */
    sealed class QuickLogMessage {
        data class Watered(val plantName: String) : QuickLogMessage()
        data class Fertilized(val plantName: String) : QuickLogMessage()
        data class WateredAndFertilized(val plantName: String) : QuickLogMessage()
    }

    class Factory(
        private val plantRepository: PlantRepository,
        private val careLogRepository: CareLogRepository,
        private val plantPhotoRepository: PlantPhotoRepository,
        private val plantId: Long,
        private val dataStore: DataStore<Preferences>,
        private val quickLogUseCase: QuickLogUseCase
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlantDetailViewModel(
                plantRepository,
                careLogRepository,
                plantPhotoRepository,
                plantId,
                dataStore,
                quickLogUseCase
            ) as T
    }
}
