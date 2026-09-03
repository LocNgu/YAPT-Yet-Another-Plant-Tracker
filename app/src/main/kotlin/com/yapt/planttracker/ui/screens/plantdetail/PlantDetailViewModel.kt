package com.yapt.planttracker.ui.screens.plantdetail

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
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
import com.yapt.planttracker.domain.model.RescheduleReason
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringReason
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.WateringExplanation
import com.yapt.planttracker.domain.schedule.WateringExplanationBuilder
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeFlow
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

@Suppress("LongParameterList")
class PlantDetailViewModel(
    internal val plantRepository: PlantRepository,
    internal val careLogRepository: CareLogRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    internal val plantId: Long,
    internal val dataStore: DataStore<Preferences>,
    internal val quickLogUseCase: QuickLogUseCase,
    internal val customReminderRepository: CustomReminderRepository,
    internal val plantIssueRepository: PlantIssueRepository,
    internal val database: PlantDatabase,
    internal val wateringAdjustmentRepository: WateringAdjustmentRepository
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

    /** Gates the "Pin interval" switch on the inline Water tab settings card — mirrors [tabsEnabled]'s pattern. */
    val seasonalWateringEnabled: StateFlow<Boolean> = dataStore.data
        .map { prefs ->
            prefs[FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING)]
                ?: FeatureFlagRegistry.SEASONAL_WATERING.default
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Gates the "Why this date?" sheet's base/season/confidence/adjustments rows (#572). */
    val adaptiveWateringEnabled: StateFlow<Boolean> = dataStore.data
        .map { prefs ->
            prefs[FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING)]
                ?: FeatureFlagRegistry.ADAPTIVE_WATERING.default
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Raw global amplitude value (0.0 when the flag is off) for the seasonal-curve preview chart
     * (#579) shown alongside the "Pin interval" switch — reuses the same choke point [careStatus]
     * reads, rather than re-deriving amplitude at this call site.
     */
    val seasonalAmplitudeValue: StateFlow<Double> = dataStore.seasonalAmplitudeFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

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
        activeIssues,
        dataStore.seasonalAmplitudeFlow()
    ) { p, logs, reminders, issues, seasonalAmplitude ->
        p ?: return@combine null
        val lastWatering = logs.firstOrNull { it.careType == CareType.WATER }
        val lastFertilizing = logs.firstOrNull { it.careType == CareType.FERTILIZE }
        CareSchedule.computeStatus(
            plant = p,
            lastWateredAt = lastWatering?.loggedAt,
            lastFertilizedAt = lastFertilizing?.loggedAt,
            totalLogs = logs.size,
            customReminders = reminders,
            seasonalAmplitude = seasonalAmplitude
        ).copy(activeIssueCount = issues.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val customReminderStatuses: StateFlow<List<CustomReminderStatus>> = careStatus
        .map { it?.customReminderStatuses.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val waterLogCount: StateFlow<Int> = careLogs
        .map { logs -> logs.count { it.careType == CareType.WATER } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val recentWateringAdjustments: StateFlow<List<WateringAdjustment>> =
        wateringAdjustmentRepository.getRecentForPlant(plantId, RECENT_ADJUSTMENTS_LIMIT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Everything the "Why this date?" sheet (#572) renders — built by [WateringExplanationBuilder]
     * from [careStatus] (already computed by [CareSchedule.computeStatus]) so the sheet's numbers can
     * never drift from what actually drove the due date.
     */
    val wateringExplanation: StateFlow<WateringExplanation?> = combine(
        combine(plant, careStatus, waterLogCount) { p, status, count -> Triple(p, status, count) },
        combine(
            adaptiveWateringEnabled,
            seasonalAmplitudeValue,
            recentWateringAdjustments
        ) { adaptiveOn, amplitude, adjustments ->
            Triple(adaptiveOn, amplitude, adjustments)
        }
    ) { (p, status, waterCount), (adaptiveOn, amplitude, adjustments) ->
        p ?: return@combine null
        WateringExplanationBuilder.build(
            plant = p,
            nextWateringDueAt = status?.nextWateringDueAt,
            lastWateredAt = status?.lastWateredAt,
            waterLogCount = waterCount,
            adaptiveWateringEnabled = adaptiveOn,
            seasonalAmplitude = amplitude,
            recentAdjustments = adjustments,
            rescheduleDeltaDays = status?.rescheduleDeltaDays
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val suggestedWateringInterval = MutableStateFlow<Int?>(null)

    /**
     * The ADR-0006 dialog's raw+converted+current interval numbers, bundled into one atomically-
     * updating tuple (#620 round 2) rather than three independently `collectAsStateWithLifecycle()`-
     * collected `StateFlow`s: `suggestedWateringInterval` updates synchronously off the raw
     * `MutableStateFlow`, while a derived value built through an extra `combine()` hop can lag it by a
     * frame — long enough to transiently render an unconverted number. [effectiveIntervalDays] is
     * [rawIntervalDays] treated as the plant's new base and run through the same base→effective
     * conversion [wateringExplanation] already uses ([CareSchedule.effectiveWateringIntervalDaysForDisplay]),
     * so the two rows can't drift. A single one-way conversion — no round-trip, no double-rounding.
     */
    data class PendingWateringSuggestion(
        val rawIntervalDays: Int,
        val effectiveIntervalDays: Int,
        val currentIntervalDays: Int?
    )

    /**
     * `null` whenever there's no pending suggestion, or the suggestion's effective-space value equals
     * [Plant.wateringIntervalDays] — the entire "jump" was a base/effective unit-mismatch artifact
     * (#620), not a real model change, so the dialog shouldn't appear at all. The dialog's editable text
     * field stays bound to the raw [suggestedWateringInterval] (fine-tuning the model's base, not a
     * literal effective override) — this flow is display/gating-only.
     */
    val pendingWateringSuggestion: StateFlow<PendingWateringSuggestion?> = combine(
        plant,
        suggestedWateringInterval,
        seasonalAmplitudeValue
    ) { p, suggestion, amplitude ->
        if (p == null || suggestion == null) return@combine null
        val effective = CareSchedule.effectiveWateringIntervalDaysForDisplay(
            plant = p.copy(wateringBaseIntervalDays = suggestion.toDouble(), wateringIntervalDays = suggestion),
            seasonalAmplitude = amplitude
        ) ?: suggestion
        if (effective == p.wateringIntervalDays) return@combine null
        PendingWateringSuggestion(
            rawIntervalDays = suggestion,
            effectiveIntervalDays = effective,
            currentIntervalDays = p.wateringIntervalDays
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    internal val selectedTimeRange = MutableStateFlow(TimeRange.TWELVE_MONTHS)

    val showRescheduleDialog = MutableStateFlow(false)

    /**
     * The Reschedule reason prompt (#586, product ADR-0030), shown *before*
     * [showRescheduleDialog] — the reason decides what the model learns, and (for "Soil still moist")
     * what date the picker opens on, so it has to be answered first.
     */
    val showRescheduleReasonSheet = MutableStateFlow(false)

    /** The answer to [showRescheduleReasonSheet], held while the date dialog is up. */
    val rescheduleReason = MutableStateFlow<RescheduleReason?>(null)

    /**
     * The recommended deferral shown at the top of [RescheduleWateringDialog], non-null only for a
     * "Soil still moist" reschedule — see [QuickLogUseCase.suggestedStillMoistDeferralDays].
     */
    val rescheduleSuggestedDays = MutableStateFlow<Int?>(null)

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

    /** Lets the extracted `PlantDetail*Actions.kt` extension functions emit without widening [_events] itself. */
    internal suspend fun emitEvent(event: Event) = _events.emit(event)

    /** Lets the extracted `PlantDetail*Actions.kt` extension functions emit without widening [_quickLogMessage]. */
    internal suspend fun emitQuickLogMessage(message: QuickLogMessage) = _quickLogMessage.emit(message)

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
     * Quick-logs a watering with [reason] from the tappable watering stat chip (`null` when the
     * watering was on schedule and no reason prompt appeared, #586). Reuses the shared
     * [QuickLogUseCase] so behaviour matches the PlantList/Calendar quick-water paths; any adaptive
     * interval suggestion feeds the existing interval-suggestion dialog via [suggestedWateringInterval].
     */
    fun quickWater(reason: WateringReason?) {
        viewModelScope.launch {
            val p = plant.value ?: return@launch
            val outcome = quickLogUseCase.quickWaterWithReason(p, reason)
            if (!outcome.logged) {
                _quickLogMessage.emit(QuickLogMessage.AlreadyWateredToday(p.name))
                return@launch
            }
            outcome.suggestion?.let { applySuggestionOrPrompt(it.suggestedInterval) }
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
    fun quickLiquidFertilize(reason: WateringReason?) {
        viewModelScope.launch {
            val p = plant.value ?: return@launch
            val outcome = quickLogUseCase.quickLiquidFertilizeWithReason(p, reason)
            if (!outcome.logged) {
                _quickLogMessage.emit(QuickLogMessage.AlreadyFertilizedToday(p.name))
                return@launch
            }
            outcome.suggestion?.let { applySuggestionOrPrompt(it.suggestedInterval) }
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

        /** "Recent adjustments" row cap on the "Why this date?" sheet (#572) — mirrors care history's cap. */
        const val RECENT_ADJUSTMENTS_LIMIT = 5
    }

    sealed class Event {
        object IntervalUpdated : Event()

        /**
         * A suggestion applied silently because "Ask before changing intervals" is off (#572).
         * [beforeIntervalDays]/[beforeBaseIntervalDays] are the plant's actual prior
         * [Plant.wateringIntervalDays]/[Plant.wateringBaseIntervalDays], for `undoSilentIntervalApply`
         * to restore exactly (#626) — never recomputed at the call site. [afterIntervalDays] is the
         * effective value `applySuggestionOrPrompt` actually wrote, not the raw base-space suggestion.
         */
        data class SilentIntervalApplied(
            val beforeIntervalDays: Int,
            val beforeBaseIntervalDays: Double?,
            val afterIntervalDays: Int
        ) : Event()

        /**
         * `revertReschedule` cleared [Plant.wateringDueDateOverride] (#630). [previousOverrideAtMillis]
         * is the plant's actual prior override value, for `undoRevertReschedule` to restore exactly —
         * never recomputed at the call site.
         */
        data class RescheduleReverted(val previousOverrideAtMillis: Long) : Event()
    }

    /** One-shot snackbar messages emitted after a quick-log from the tappable stat chips or watering-due actions row. */
    sealed class QuickLogMessage {
        data class Watered(val plantName: String) : QuickLogMessage()
        data class Fertilized(val plantName: String) : QuickLogMessage()
        data class WateredAndFertilized(val plantName: String) : QuickLogMessage()
        data class AlreadyWateredToday(val plantName: String) : QuickLogMessage()
        data class AlreadyFertilizedToday(val plantName: String) : QuickLogMessage()

        /** "Still moist" logged successfully (#508). */
        data class StillMoistChecked(val plantName: String) : QuickLogMessage()

        /** [plant] already has a CHECK log today (#508, mirrors [AlreadyWateredToday]'s dedupe guard). */
        data class AlreadyCheckedToday(val plantName: String) : QuickLogMessage()
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
        private val plantIssueRepository: PlantIssueRepository,
        private val database: PlantDatabase,
        private val wateringAdjustmentRepository: WateringAdjustmentRepository
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
                plantIssueRepository,
                database,
                wateringAdjustmentRepository
            ) as T
    }
}
