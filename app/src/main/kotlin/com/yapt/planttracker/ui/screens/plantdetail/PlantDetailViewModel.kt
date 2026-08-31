package com.yapt.planttracker.ui.screens.plantdetail

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
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
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringReason
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.WateringExplanation
import com.yapt.planttracker.domain.schedule.WateringExplanationBuilder
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeFlow
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.ui.components.TimeRange
import com.yapt.planttracker.util.toLocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@Suppress("LongParameterList")
class PlantDetailViewModel(
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
            recentAdjustments = adjustments
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

    /**
     * Plain reset of the raw suggestion with no confidence side effect — as opposed to
     * [dismissSuggestedInterval], the explicit Dismiss tap. [PlantDetailScreen] no longer calls this
     * to silently pre-empt a stale suggestion before it renders (#620 round 2) — [pendingWateringSuggestion]
     * already collapses to `null` by itself whenever the effective-space delta is 0, so a screen-side
     * short-circuit against the raw value would only risk discarding a suggestion that is genuinely
     * different in effective space but happens to numerically coincide with it in base space.
     */
    fun clearSuggestedInterval() {
        suggestedWateringInterval.value = null
    }

    /**
     * Dismissing the ADR-0006 suggestion dialog without applying (explicit Dismiss tap, or tapping
     * outside it). A genuine dismissal raises [Plant.wateringConfidence] up to
     * [CareSchedule.DISMISSAL_CONFIDENCE_CEILING] when [FeatureFlagRegistry.ADAPTIVE_WATERING] is on
     * (#568) — the user is saying the current schedule is fine.
     */
    fun dismissSuggestedInterval() {
        viewModelScope.launch {
            if (isAdaptiveWateringEnabled()) {
                plant.value?.let { p ->
                    plantRepository.updatePlant(
                        p.copy(
                            wateringConfidence = CareSchedule.confidenceAfterDismissal(p.wateringConfidence),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    p.wateringIntervalDays?.let { current ->
                        // #584 review: log the base-space reference, not the literal effective
                        // value, so this row's units match the WATER_*/CHECK_STILL_MOIST rows when
                        // season is on and the plant isn't pinned.
                        val currentBase = currentBaseIntervalDaysOrLiteral(p, current)
                        wateringAdjustmentRepository.addAdjustment(
                            WateringAdjustment(
                                plantId = p.id,
                                trigger = WateringAdjustmentTrigger.DIALOG_DISMISSAL,
                                beforeIntervalDays = currentBase,
                                afterIntervalDays = currentBase
                            )
                        )
                    }
                }
            }
            suggestedWateringInterval.value = null
        }
    }

    private suspend fun isAdaptiveWateringEnabled(): Boolean =
        dataStore.data.first()[FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING)]
            ?: FeatureFlagRegistry.ADAPTIVE_WATERING.default

    /**
     * "Ask before changing intervals" (#572) — the ADR-0006 dialog is skipped only when
     * `adaptive_watering` is on **and** the setting is off; the toggle is inert while the flag is off
     * (today's dialog-always behavior).
     */
    private suspend fun shouldShowIntervalDialog(): Boolean {
        if (!isAdaptiveWateringEnabled()) return true
        return dataStore.data.first()[SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS] ?: true
    }

    /**
     * Routes a freshly-computed adaptive suggestion to either the ADR-0006 dialog or a silent apply
     * + undo Snackbar, depending on [shouldShowIntervalDialog] (#572).
     */
    private suspend fun applySuggestionOrPrompt(suggestedInterval: Int) {
        if (shouldShowIntervalDialog()) {
            suggestedWateringInterval.value = suggestedInterval
            return
        }
        val p = plant.value ?: return
        val before = applyIntervalInternal(p, originalSuggestion = suggestedInterval, newInterval = suggestedInterval)
        _events.emit(Event.SilentIntervalApplied(before, suggestedInterval))
    }

    /** Entry point for the ADR-0006 suggestion surfaced via `AddCareLogScreen`'s save flow (see `NavGraph`). */
    fun handleSuggestedWateringInterval(suggestedInterval: Int) {
        viewModelScope.launch { applySuggestionOrPrompt(suggestedInterval) }
    }

    internal fun setTimeRange(range: TimeRange) {
        selectedTimeRange.value = range
    }

    /**
     * The single write path for committing a new [Plant.wateringIntervalDays] from an adaptive
     * suggestion (#572) — used by both the ADR-0006 dialog's Apply button and the silent-apply path.
     * Adopts the same dual-write [setWateringInterval] already uses for manual edits (§1 of the #572
     * spec: applying a suggestion with `SEASONAL_WATERING` on previously left
     * [Plant.wateringBaseIntervalDays] stale, so the due date silently never moved). Returns the
     * pre-apply interval, for the silent-apply Snackbar's undo.
     */
    private suspend fun applyIntervalInternal(plant: Plant, originalSuggestion: Int?, newInterval: Int): Int {
        val now = System.currentTimeMillis()
        val adaptiveOn = isAdaptiveWateringEnabled()
        // Retyping the suggested number before tapping Apply is fine-tuning within the model, not a
        // rejection of it — never a full reset like an AddEditPlant edit (#568). Outside
        // GAP_AGREEMENT_TOLERANCE of the original suggestion, the suggestion was materially wrong and
        // confidence falls, but the model still stands. A silent apply always passes
        // originalSuggestion == newInterval, so confidence never falls from an apply the user never edited.
        val wateringConfidence = if (adaptiveOn && originalSuggestion != null) {
            CareSchedule.confidenceAfterDialogEdit(plant.wateringConfidence, originalSuggestion, newInterval)
        } else {
            plant.wateringConfidence
        }
        // newInterval is already season-neutral (base-space) when SEASONAL_WATERING is also on — it's
        // QuickLogUseCase's adaptive suggestion, computed entirely from already-deseasonalized inputs
        // (unlike setWateringInterval's `days` param below, which is a literal effective value the user
        // just typed and genuinely needs deseasonalizing). Re-deseasonalizing it here would
        // double-divide by season() (#584 review round 1). But ADAPTIVE_WATERING/SEASONAL_WATERING are
        // independent flags — when amplitude is 0, newInterval is a *literal* value, not base-space, so
        // writing it straight into wateringBaseIntervalDays would clobber a real prior base. Gate on
        // amplitude too, matching setWateringInterval/currentBaseIntervalDaysOrLiteral (#584 review
        // round 2).
        val wateringBaseIntervalDays = if (!plant.pinIntervalToBase && dataStore.seasonalAmplitudeOnce() != 0.0) {
            newInterval.toDouble()
        } else {
            plant.wateringBaseIntervalDays
        }
        val before = plant.wateringIntervalDays ?: newInterval
        plantRepository.updatePlant(
            plant.copy(
                wateringIntervalDays = newInterval,
                wateringBaseIntervalDays = wateringBaseIntervalDays,
                wateringConfidence = wateringConfidence,
                updatedAt = now
            )
        )
        if (adaptiveOn) {
            // #584 review: `before` is `plant.wateringIntervalDays`, which may still be a literal
            // effective value (e.g. never dual-written by this function before) rather than
            // base-space — read the plant's actual current base for the row instead.
            wateringAdjustmentRepository.addAdjustment(
                WateringAdjustment(
                    plantId = plant.id,
                    triggeredAt = now,
                    trigger = WateringAdjustmentTrigger.DIALOG_EDIT,
                    beforeIntervalDays = currentBaseIntervalDaysOrLiteral(plant, before),
                    afterIntervalDays = newInterval
                )
            )
        }
        return before
    }

    fun applySuggestedInterval(newInterval: Int) {
        viewModelScope.launch {
            val originalSuggestion = suggestedWateringInterval.value
            plant.value?.let { p -> applyIntervalInternal(p, originalSuggestion, newInterval) }
            suggestedWateringInterval.value = null
            _events.emit(Event.IntervalUpdated)
        }
    }

    /**
     * Reverts a silently-applied suggestion (#572) back to [beforeIntervalDays] — the Snackbar's
     * "Undo" action. Writes a compensating [WateringAdjustment] row ([WateringAdjustmentTrigger
     * .SILENT_APPLY_UNDONE], #584 review) so "Recent adjustments" reflects the revert instead of
     * still showing the original silent apply as if it stood — `before` is the silently-applied
     * value being undone, `after` is the restored original.
     */
    fun undoSilentIntervalApply(beforeIntervalDays: Int) {
        viewModelScope.launch {
            plant.value?.let { p ->
                // beforeIntervalDays is the prior wateringIntervalDays captured by applyIntervalInternal,
                // only genuinely base-space when SEASONAL_WATERING was on at that time too — same
                // double-deseasonalization pitfall applies here (assign directly, never through
                // deseasonalizedBaseOrNull), and the same amplitude gate applies too, otherwise this
                // would clobber a real prior base with a literal value (#584 review round 2).
                val wateringBaseIntervalDays = if (!p.pinIntervalToBase && dataStore.seasonalAmplitudeOnce() != 0.0) {
                    beforeIntervalDays.toDouble()
                } else {
                    p.wateringBaseIntervalDays
                }
                val silentlyAppliedInterval = p.wateringIntervalDays ?: beforeIntervalDays
                val now = System.currentTimeMillis()
                plantRepository.updatePlant(
                    p.copy(
                        wateringIntervalDays = beforeIntervalDays,
                        wateringBaseIntervalDays = wateringBaseIntervalDays,
                        updatedAt = now
                    )
                )
                if (isAdaptiveWateringEnabled()) {
                    wateringAdjustmentRepository.addAdjustment(
                        WateringAdjustment(
                            plantId = p.id,
                            triggeredAt = now,
                            trigger = WateringAdjustmentTrigger.SILENT_APPLY_UNDONE,
                            beforeIntervalDays = silentlyAppliedInterval,
                            afterIntervalDays = beforeIntervalDays
                        )
                    )
                }
            }
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
                // De-seasonalize the newly set value to today (#569), mirroring AddEditPlant's
                // manual-edit handling — unchanged when SEASONAL_WATERING is off, the plant is
                // pinned, or the schedule was just switched off (`days == null`); the prior base
                // (if any) is preserved rather than cleared.
                val deseasonalizedDays = if (days != null && !p.pinIntervalToBase) {
                    deseasonalizedBaseOrNull(days)
                } else {
                    null
                }
                val wateringBaseIntervalDays = if (days != null && !p.pinIntervalToBase) {
                    deseasonalizedDays ?: p.wateringBaseIntervalDays
                } else {
                    p.wateringBaseIntervalDays
                }
                val now = System.currentTimeMillis()
                plantRepository.updatePlant(
                    p.copy(
                        wateringIntervalDays = days,
                        wateringBaseIntervalDays = wateringBaseIntervalDays,
                        updatedAt = now
                    )
                )
                if (days != null && days != p.wateringIntervalDays && isAdaptiveWateringEnabled()) {
                    // #584 review: log the base-space before/after, not the literal typed value. This
                    // deliberately does *not* reuse `wateringBaseIntervalDays` above for "after" — that
                    // preserves a stale prior base when season is off, whereas the log's "after" must
                    // collapse to the literal `days` in that case (mirrors the "before" side's collapse).
                    val loggedAfter = if (!p.pinIntervalToBase) {
                        (deseasonalizedDays ?: days.toDouble()).roundToInt()
                    } else {
                        days
                    }
                    wateringAdjustmentRepository.addAdjustment(
                        WateringAdjustment(
                            plantId = p.id,
                            triggeredAt = now,
                            trigger = WateringAdjustmentTrigger.MANUAL_EDIT,
                            beforeIntervalDays = currentBaseIntervalDaysOrLiteral(p, p.wateringIntervalDays ?: days),
                            afterIntervalDays = loggedAfter
                        )
                    )
                }
            }
        }
    }

    /** "Pin interval" switch on the inline Water tab settings card (#569) — see [seasonalWateringEnabled]. */
    fun setPinIntervalToBase(pinned: Boolean) {
        viewModelScope.launch {
            plant.value?.let { p ->
                plantRepository.updatePlant(p.copy(pinIntervalToBase = pinned, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    private suspend fun deseasonalizedBaseOrNull(intervalDays: Int): Double? {
        val amplitude = dataStore.seasonalAmplitudeOnce()
        if (amplitude == 0.0) return null
        return SeasonalWatering.deseasonalize(
            intervalDays.toDouble(),
            System.currentTimeMillis().toLocalDate(),
            amplitude,
            SeasonalWatering.currentHemisphere()
        )
    }

    /**
     * [plant]'s current base-space reference for [WateringAdjustment] row units (#584 review) —
     * mirrors [com.yapt.planttracker.domain.usecase.QuickLogUseCase]'s
     * `currentAdaptiveBaseIntervalDays()` fallback. Collapses to [literal] itself when the plant is
     * pinned or SEASONAL_WATERING is off, matching every other read of [Plant.wateringBaseIntervalDays].
     */
    @Suppress("ReturnCount")
    private suspend fun currentBaseIntervalDaysOrLiteral(plant: Plant, literal: Int): Int {
        if (plant.pinIntervalToBase) return literal
        val amplitude = dataStore.seasonalAmplitudeOnce()
        if (amplitude == 0.0) return literal
        return (plant.wateringBaseIntervalDays ?: literal.toDouble()).roundToInt()
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
     * this issue never touches the linked reminder, which keeps running independently. Both writes
     * run inside a single [PlantDatabase] transaction (mirroring [QuickLogUseCase]'s paired-write
     * precedent) so a killed process or DB error between the two inserts can never leave an orphan
     * [CustomReminder] with no [PlantIssue] pointing at it.
     */
    fun reportIssue(name: String, reminderName: String?, reminderIntervalDays: Int?) {
        viewModelScope.launch {
            database.withTransaction {
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

    /** Opens the #586 reason prompt; the date dialog only follows once a reason is chosen. */
    fun requestReschedule() {
        showRescheduleReasonSheet.value = true
    }

    /**
     * Dismissing the reason prompt abandons the whole reschedule — no override write, no log, no
     * model effect. "Records no signal" is satisfied here by recording nothing at all (#586).
     */
    fun dismissRescheduleReasonSheet() {
        showRescheduleReasonSheet.value = false
        rescheduleReason.value = null
        rescheduleSuggestedDays.value = null
    }

    /**
     * Answer to the #586 reason prompt. For [RescheduleReason.SOIL_STILL_MOIST] the date picker opens
     * on a deferral derived from the interval the model lands on after that observation, rather than
     * on today or #570's flat +1 day; for [RescheduleReason.CANT_RIGHT_NOW] there is nothing to
     * suggest, because nothing about the plant was observed.
     */
    fun chooseRescheduleReason(reason: RescheduleReason) {
        viewModelScope.launch {
            rescheduleReason.value = reason
            rescheduleSuggestedDays.value = plant.value
                ?.takeIf { reason == RescheduleReason.SOIL_STILL_MOIST }
                ?.let { quickLogUseCase.suggestedStillMoistDeferralDays(it) }
            showRescheduleReasonSheet.value = false
            showRescheduleDialog.value = true
        }
    }

    fun dismissRescheduleDialog() {
        showRescheduleDialog.value = false
        rescheduleReason.value = null
        rescheduleSuggestedDays.value = null
    }

    /**
     * Reschedule "Today" option (#508, product ADR-0029) — only ever tapped from an enabled state,
     * since the screen disables it while [PlantCareStatus.isDueSoon] (already due today, a true
     * no-op there), and also while the reason is "Soil still moist", where pulling the date forward
     * would contradict what the user just said. See [applyReschedule].
     */
    fun confirmRescheduleToday() {
        applyReschedule(System.currentTimeMillis())
    }

    /**
     * Reschedule "+[days]" option (#508, product ADR-0029) — anchored to the current *effective* due
     * date (`maxOf(nextWateringDueAt, now)`, already override-aware via [CareSchedule]), unchanged
     * from the stepper dialog this replaces. [days] never affects what the model learns (#586).
     */
    fun confirmRescheduleRelativeDays(days: Int) {
        val currentDue = maxOf(careStatus.value?.nextWateringDueAt ?: 0L, System.currentTimeMillis())
        applyReschedule(currentDue + TimeUnit.DAYS.toMillis(days.toLong()))
    }

    /**
     * Reschedule "Custom date…" option (#508, product ADR-0029) — [newDueAtMillis] is the user-picked
     * date at local start-of-day; the `DatePicker` itself excludes past dates via `SelectableDates`,
     * so no further validation happens here.
     */
    fun confirmRescheduleCustomDate(newDueAtMillis: Long) {
        applyReschedule(newDueAtMillis)
    }

    /**
     * The one place a reschedule is committed, whichever date option was tapped. What the answer to
     * the #586 reason prompt decides — never the length of the deferral:
     *
     * - **"Soil still moist"** routes through the same [QuickLogUseCase.recordStillMoistCheck] the
     *   notification's Still-moist action calls, so the two paths produce identical `CareType.CHECK`
     *   logs and model effects by construction rather than by two implementations happening to agree.
     * - **"I can't right now"** writes [Plant.wateringDueDateOverride] only — never
     *   [Plant.wateringIntervalDays] / [Plant.wateringBaseIntervalDays] / [Plant.wateringConfidence],
     *   and never a [WateringAdjustment] row. That is ADR-0029's posture for *every* reschedule,
     *   preserved here for the half of them that really is about the user's availability.
     *
     * Neither path ever fires the ADR-0006 interval-suggestion dialog.
     */
    private fun applyReschedule(newDueAtMillis: Long) {
        viewModelScope.launch {
            val reason = rescheduleReason.value
            showRescheduleDialog.value = false
            rescheduleReason.value = null
            rescheduleSuggestedDays.value = null
            val p = plant.value ?: return@launch
            if (reason == RescheduleReason.SOIL_STILL_MOIST) {
                val logged = quickLogUseCase.recordStillMoistCheck(p, newDueAtMillis)
                _quickLogMessage.emit(
                    if (logged) {
                        QuickLogMessage.StillMoistChecked(p.name)
                    } else {
                        QuickLogMessage.AlreadyCheckedToday(p.name)
                    }
                )
            } else {
                plantRepository.updatePlant(
                    p.copy(wateringDueDateOverride = newDueAtMillis, updatedAt = System.currentTimeMillis())
                )
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

        /** "Recent adjustments" row cap on the "Why this date?" sheet (#572) — mirrors care history's cap. */
        const val RECENT_ADJUSTMENTS_LIMIT = 5
    }

    sealed class Event {
        object IntervalUpdated : Event()

        /** A suggestion applied silently because "Ask before changing intervals" is off (#572). */
        data class SilentIntervalApplied(val beforeIntervalDays: Int, val afterIntervalDays: Int) : Event()
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
