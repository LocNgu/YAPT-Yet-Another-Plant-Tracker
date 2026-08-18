package com.yapt.planttracker.ui.screens.plantdetail

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.CustomReminderStatus
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.model.GalleryPhotoSource
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.PlantIssue
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

@Suppress("LongParameterList")
class PlantDetailViewModel(
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val plantId: Long,
    private val dataStore: DataStore<Preferences>,
    private val quickLogUseCase: QuickLogUseCase,
    private val customReminderRepository: CustomReminderRepository,
    private val plantIssueRepository: PlantIssueRepository
) : ViewModel() {

    /**
     * Whether the per-action tabs, inline scheduling settings, and per-tab insights (#436) are
     * shown. Behind [FeatureFlagRegistry.PLANT_DETAIL_TABS] (developer mode → feature flags, product
     * ADR-0022); when off the screen renders the classic single-page layout.
     *
     * Read straight from [dataStore] via [FeatureFlags.preferenceKeyFor] — the same key derivation
     * the [FeatureFlags] singleton writes through, so the two can't drift — rather than taking a
     * `FeatureFlags` constructor parameter, which would push this constructor to 7 params and trip
     * Detekt's `LongParameterList` (the same constraint #521 hit on `SettingsViewModel`). Mirrors how
     * [photoReminderEnabled] already reads its own preference here.
     */
    val tabsEnabled: StateFlow<Boolean> = dataStore.data
        .map { prefs ->
            prefs[FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.PLANT_DETAIL_TABS)]
                ?: FeatureFlagRegistry.PLANT_DETAIL_TABS.default
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val plant: StateFlow<Plant?> = plantRepository.getPlantById(plantId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val careLogs: StateFlow<List<CareLog>> = careLogRepository.getLogsForPlant(plantId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customReminders: StateFlow<List<CustomReminder>> = customReminderRepository.getRemindersForPlant(plantId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Currently-unresolved [PlantIssue]s for the "Active Issues" card (issue #564). */
    val activeIssues: StateFlow<List<PlantIssue>> = plantIssueRepository.getActiveIssuesForPlant(plantId)
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

    val careStatus: StateFlow<PlantCareStatus?> = combine(
        plant,
        careLogs,
        customReminders,
        activeIssues
    ) { p, logs, reminders, issues ->
        p ?: return@combine null
        val lastWatering = logs.firstOrNull { it.careType == CareType.WATER }
        val lastFertilizing = logs.firstOrNull { it.careType == CareType.FERTILIZE }
        CareSchedule.computeStatus(
            plant = p,
            lastWateredAt = lastWatering?.loggedAt,
            lastFertilizedAt = lastFertilizing?.loggedAt,
            totalLogs = logs.size,
            customReminders = reminders
        ).copy(activeIssueCount = issues.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val customReminderStatuses: StateFlow<List<CustomReminderStatus>> = careStatus
        .map { it?.customReminderStatuses.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    fun quickWater(feedback: WateringFeedback?) {
        viewModelScope.launch {
            val p = plant.value ?: return@launch
            val outcome = quickLogUseCase.quickWaterWithFeedback(p, feedback)
            if (!outcome.logged) {
                _quickLogMessage.emit(QuickLogMessage.AlreadyWateredToday(p.name))
                return@launch
            }
            outcome.suggestion?.let { suggestedWateringInterval.value = it.suggestedInterval }
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
            val outcome = quickLogUseCase.quickLog(p, CareType.FERTILIZE)
            if (!outcome.logged) {
                _quickLogMessage.emit(QuickLogMessage.AlreadyFertilizedToday(p.name))
                return@launch
            }
            val message = if (outcome.waterPaired) {
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
    fun quickLiquidFertilize(feedback: WateringFeedback?) {
        viewModelScope.launch {
            val p = plant.value ?: return@launch
            val outcome = quickLogUseCase.quickLiquidFertilizeWithFeedback(p, feedback)
            if (!outcome.logged) {
                _quickLogMessage.emit(QuickLogMessage.AlreadyFertilizedToday(p.name))
                return@launch
            }
            outcome.suggestion?.let { suggestedWateringInterval.value = it.suggestedInterval }
            val message = if (outcome.waterPaired) {
                QuickLogMessage.WateredAndFertilized(p.name)
            } else {
                QuickLogMessage.Fertilized(p.name)
            }
            _quickLogMessage.emit(message)
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

    /** Adds a new custom reminder (#232) — free-text [name] plus a plain-days [intervalDays]. */
    fun addCustomReminder(name: String, intervalDays: Int) {
        viewModelScope.launch {
            customReminderRepository.addReminder(
                CustomReminder(plantId = plantId, name = name, intervalDays = intervalDays)
            )
        }
    }

    /** Renames/re-intervals an existing custom reminder without touching its [CustomReminder.lastDoneAt]. */
    fun updateCustomReminder(reminder: CustomReminder, name: String, intervalDays: Int) {
        viewModelScope.launch {
            customReminderRepository.updateReminder(reminder.copy(name = name, intervalDays = intervalDays))
        }
    }

    fun deleteCustomReminder(reminder: CustomReminder) {
        viewModelScope.launch { customReminderRepository.deleteReminder(reminder) }
    }

    /**
     * Marks a custom reminder done: writes a [CareType.CUSTOM] [CareLog] linked back to it (visible
     * in the journal) and resets its [CustomReminder.lastDoneAt], mirroring how logging a built-in
     * care type resets its schedule (#232).
     */
    fun markCustomReminderDone(reminder: CustomReminder) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            careLogRepository.addLog(
                CareLog(
                    plantId = reminder.plantId,
                    careType = CareType.CUSTOM,
                    loggedAt = now,
                    customReminderId = reminder.id
                )
            )
            customReminderRepository.updateReminder(reminder.copy(lastDoneAt = now))
        }
    }

    /**
     * Reports a new plant issue (#564). When [reminderName] and [reminderIntervalDays] are both
     * non-null (the optional "set a treatment reminder" sub-section was filled in), a [CustomReminder]
     * is created first and its id stored on [PlantIssue.linkedReminderId] — a one-way, unenforced
     * link (see technical ADR-0019's `CareLog.customReminderId` precedent): resolving or deleting
     * this issue never touches the linked reminder, which keeps running independently.
     */
    fun reportIssue(name: String, reminderName: String?, reminderIntervalDays: Int?) {
        viewModelScope.launch {
            val linkedReminderId = if (reminderName != null && reminderIntervalDays != null) {
                customReminderRepository.addReminder(
                    CustomReminder(plantId = plantId, name = reminderName, intervalDays = reminderIntervalDays)
                )
            } else {
                null
            }
            plantIssueRepository.addIssue(
                PlantIssue(plantId = plantId, name = name, linkedReminderId = linkedReminderId)
            )
        }
    }

    /** Marks [issue] resolved with an optional free-text [resolutionNote] (#564). */
    fun resolveIssue(issue: PlantIssue, resolutionNote: String?) {
        viewModelScope.launch {
            plantIssueRepository.updateIssue(
                issue.copy(
                    resolvedAt = System.currentTimeMillis(),
                    resolutionNote = resolutionNote?.takeIf { it.isNotBlank() }
                )
            )
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
        data class AlreadyWateredToday(val plantName: String) : QuickLogMessage()
        data class AlreadyFertilizedToday(val plantName: String) : QuickLogMessage()
    }

    @Suppress("LongParameterList")
    class Factory(
        private val plantRepository: PlantRepository,
        private val careLogRepository: CareLogRepository,
        private val plantPhotoRepository: PlantPhotoRepository,
        private val plantId: Long,
        private val dataStore: DataStore<Preferences>,
        private val quickLogUseCase: QuickLogUseCase,
        private val customReminderRepository: CustomReminderRepository,
        private val plantIssueRepository: PlantIssueRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlantDetailViewModel(
                plantRepository,
                careLogRepository,
                plantPhotoRepository,
                plantId,
                dataStore,
                quickLogUseCase,
                customReminderRepository,
                plantIssueRepository
            ) as T
    }
}
