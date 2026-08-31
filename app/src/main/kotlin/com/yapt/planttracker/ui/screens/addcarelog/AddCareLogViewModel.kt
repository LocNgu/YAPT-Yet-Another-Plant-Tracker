package com.yapt.planttracker.ui.screens.addcarelog

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import com.yapt.planttracker.domain.usecase.WateringLifecycleReset
import com.yapt.planttracker.util.toLocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

// #568 added two small adaptive-watering helpers to this VM's one cohesive save flow; splitting
// them out would scatter that flow across files for no readability gain.
@Suppress("TooManyFunctions")
class AddCareLogViewModel(
    private val careLogRepository: CareLogRepository,
    private val plantRepository: PlantRepository,
    private val plantId: Long,
    private val careLogId: Long = 0L,
    // Nullable + defaulted so the many existing tests constructing this VM directly don't all need
    // updating; null is treated the same as the `adaptive_watering` flag being off (#568).
    private val dataStore: DataStore<Preferences>? = null,
    // Nullable + defaulted for the same reason as [dataStore] — never read when [dataStore] is null
    // since adjustment rows are only ever written on the adaptive branch (#572).
    private val wateringAdjustmentRepository: WateringAdjustmentRepository? = null
) : ViewModel() {

    val isEditMode = careLogId != 0L

    var selectedCareType by mutableStateOf(CareType.WATER)
    var notes by mutableStateOf("")
    var photoUri by mutableStateOf<String?>(null)
    var amount by mutableStateOf("")
    var loggedAt by mutableStateOf(System.currentTimeMillis())

    // Nothing pre-selected (#570, product ADR-0027) — the 3-way soil-state chip collapsed to one
    // optional "the plant needed it" flag (#570, reworded in #586); a defaulted JUST_RIGHT is no
    // longer written for an untouched log. Leaving it unset on an off-schedule watering is the "just
    // my timing" answer, which product ADR-0030 excludes from base learning.
    var selectedFeedback by mutableStateOf<WateringFeedback?>(null)
    var selectedFertilizerType by mutableStateOf(FertilizerType.UNSPECIFIED)
    private var customReminderId: Long? = null

    // false until async load completes in edit mode; used to key DatePickerState
    var isLoaded by mutableStateOf(!isEditMode)

    // Set on a rejected same-day WATER/FERTILIZE duplicate (#509); the screen shows this inline
    // instead of a dialog or disabling Save, so the user can adjust the date/type and retry.
    var duplicateLogError by mutableStateOf<Int?>(null)
        private set

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events

    init {
        if (isEditMode) {
            viewModelScope.launch {
                val log = careLogRepository.getLogById(careLogId) ?: run {
                    _events.emit(Event.NavigateBack)
                    return@launch
                }
                selectedCareType = log.careType
                notes = log.notes ?: ""
                amount = log.amount ?: ""
                photoUri = log.photoUri
                selectedFeedback = log.wateringFeedback
                selectedFertilizerType = log.fertilizerType
                loggedAt = log.loggedAt
                customReminderId = log.customReminderId
                isLoaded = true
            }
        } else {
            viewModelScope.launch {
                plantRepository.getPlantById(plantId).first()?.let { plant ->
                    if (plant.useLiquidFertilizer) selectedFertilizerType = FertilizerType.LIQUID
                }
            }
        }
    }

    /** Clears a previously-shown duplicate error, e.g. once the user edits the date or care type. */
    fun clearDuplicateLogError() {
        duplicateLogError = null
    }

    fun saveLog() {
        if (selectedCareType == CareType.PHOTO && photoUri == null) return
        viewModelScope.launch {
            if (isDuplicateLog()) return@launch
            duplicateLogError = null

            // Checked before the FERTILIZE insert below so it can't race against a paired WATER
            // row inserted by this same save (#509).
            val willPairWater = shouldPairWaterLog()

            careLogRepository.addLog(buildLogFromState())

            if (!isEditMode && selectedCareType == CareType.REPOT && isAdaptiveWateringEnabled()) {
                plantRepository.getPlantById(plantId).first()?.let { plant ->
                    WateringLifecycleReset.applyRepotReset(
                        plant,
                        resetAnchorMs = loggedAt,
                        plantRepository = plantRepository,
                        wateringAdjustmentRepository = wateringAdjustmentRepository
                    )
                }
            }
            if (willPairWater) insertPairedWaterLog()
            if (!isEditMode && selectedCareType == CareType.WATER) clearWateringOverrideIfActive()
            if (selectedCareType == CareType.PHOTO && photoUri != null) updateCoverPhoto()

            val suggestedInterval = if (isEditMode) null else computeSuggestedInterval()
            _events.emit(Event.Saved(suggestedInterval))
        }
    }

    /** Sets [duplicateLogError] and returns true if [selectedCareType]/[loggedAt] is a same-day WATER/FERTILIZE duplicate. */
    private suspend fun isDuplicateLog(): Boolean {
        if (selectedCareType != CareType.WATER && selectedCareType != CareType.FERTILIZE) return false
        val excludeId = if (isEditMode) careLogId else null
        val isDuplicate = careLogRepository.hasLogOfTypeOnDay(plantId, selectedCareType, loggedAt, excludeId)
        if (isDuplicate) duplicateLogError = duplicateErrorRes(selectedCareType)
        return isDuplicate
    }

    private suspend fun shouldPairWaterLog(): Boolean =
        !isEditMode &&
            selectedCareType == CareType.FERTILIZE &&
            selectedFertilizerType == FertilizerType.LIQUID &&
            !careLogRepository.hasLogOfTypeOnDay(plantId, CareType.WATER, loggedAt)

    private fun buildLogFromState() = CareLog(
        id = careLogId,
        plantId = plantId,
        careType = selectedCareType,
        loggedAt = loggedAt,
        notes = notes.trim().ifBlank { null },
        photoUri = photoUri,
        amount = amount.trim().ifBlank { null },
        wateringFeedback = if (selectedCareType == CareType.WATER) selectedFeedback else null,
        fertilizerType = if (selectedCareType == CareType.FERTILIZE) selectedFertilizerType else FertilizerType.UNSPECIFIED,
        customReminderId = customReminderId
    )

    private suspend fun insertPairedWaterLog() {
        // No reason: the user fertilized, and the watering came along with it (ADR-0008) — they were
        // never asked why they watered, so nothing is attributed (#586, product ADR-0030).
        careLogRepository.addLog(
            CareLog(
                plantId = plantId,
                careType = CareType.WATER,
                loggedAt = loggedAt,
                wateringFeedback = null
            )
        )
        clearWateringOverrideIfActive()
    }

    private suspend fun clearWateringOverrideIfActive() {
        plantRepository.getPlantById(plantId).first()?.let { p ->
            if (p.wateringDueDateOverride != null) {
                plantRepository.updatePlant(
                    p.copy(wateringDueDateOverride = null, updatedAt = System.currentTimeMillis())
                )
            }
        }
    }

    private suspend fun updateCoverPhoto() {
        plantRepository.getPlantById(plantId).first()?.let { p ->
            plantRepository.updatePlant(p.copy(coverPhotoUri = photoUri, updatedAt = System.currentTimeMillis()))
        }
    }

    @StringRes
    private fun duplicateErrorRes(careType: CareType): Int = when (careType) {
        CareType.WATER -> R.string.care_log_error_already_watered
        CareType.FERTILIZE -> R.string.care_log_error_already_fertilized
        else -> error("No duplicate guard defined for $careType")
    }

    /**
     * [selectedFeedback] is no longer required to be non-null (#570, product ADR-0027) — with the
     * chip collapse, `null` is the dominant case, and the legacy (flag-off) branch below is the only
     * one that still needs an explicit feedback value to produce a suggestion; the adaptive branch
     * accepts `null` directly (feeds `CareSchedule.NEUTRAL_TARGET_MULTIPLIER` at a capped gain).
     */
    private suspend fun computeSuggestedInterval(): Int? {
        if (selectedCareType != CareType.WATER) return null
        val feedback = selectedFeedback

        val plant = plantRepository.getPlantById(plantId).first() ?: return null
        val currentInterval = plant.wateringIntervalDays

        val lastTwoWaterings = careLogRepository.getLastTwoWaterings(plantId)
        val actualIntervalDays = if (lastTwoWaterings.size >= 2) {
            CareSchedule.daysBetween(lastTwoWaterings[1].loggedAt, lastTwoWaterings[0].loggedAt)
        } else {
            currentInterval ?: return null
        }

        if (actualIntervalDays <= 0) return null

        val suggested = if (currentInterval != null && isAdaptiveWateringEnabled()) {
            adaptWateringInterval(plant, feedback, actualIntervalDays, currentInterval)
        } else {
            feedback?.let { CareSchedule.computeSuggestedInterval(it, actualIntervalDays, currentInterval) } ?: return null
        }
        val effectiveSuggested = effectiveIntervalForDisplay(plant, suggested)
        return if (effectiveSuggested != currentInterval) suggested else null
    }

    /**
     * Gates on the **effective**-space comparison (#620 round 2), not the raw base-space [suggestion]
     * vs [currentInterval] — mirrors [com.yapt.planttracker.domain.usecase.QuickLogUseCase
     * .effectiveIntervalForDisplay]/`computeSuggestion` exactly, since this VM computes its own
     * suggestion rather than going through that use case (per this file's convention). Without this
     * gate a pure base/effective unit-mismatch artifact reaches `Event.Saved` ungated, and when
     * `askBeforeChangingIntervals` is off, `PlantDetailViewModel.applySuggestionOrPrompt()`'s
     * silent-apply branch writes it straight into `plant.wateringIntervalDays` with nothing else to
     * catch it.
     *
     * [loggedAt] is the reference date, not `LocalDate.now()` — this whole computation runs
     * synchronously as part of the same save that set [loggedAt], mirroring how
     * [deseasonalizedObservedIntervalDays] already anchors this file's other seasonal math to
     * [loggedAt] rather than the wall-clock instant the suspend function happens to run.
     */
    private suspend fun effectiveIntervalForDisplay(plant: Plant, suggestion: Int): Int =
        CareSchedule.effectiveWateringIntervalDaysForDisplay(
            plant = plant.copy(wateringBaseIntervalDays = suggestion.toDouble(), wateringIntervalDays = suggestion),
            nowDate = loggedAt.toLocalDate(),
            seasonalAmplitude = dataStore?.seasonalAmplitudeOnce() ?: 0.0
        ) ?: suggestion

    /**
     * Applies the multiplicative + confidence-weighted model (#568, technical ADR-0021) and
     * persists the resulting [Plant.wateringConfidence] immediately — confidence updates on every
     * qualifying observation, independent of whether the caller later shows/applies the resulting
     * suggestion dialog. [feedback] may be `null` (#570) — a silent gap-only observation, capped at
     * [CareSchedule.NEUTRAL_OBSERVATION_GAIN]. See [com.yapt.planttracker.domain.usecase.QuickLogUseCase]'s copy of this same function for
     * the #571 history-bootstrap short-circuit this mirrors.
     */
    private suspend fun adaptWateringInterval(
        plant: Plant,
        feedback: WateringFeedback?,
        actualIntervalDays: Int,
        currentInterval: Int
    ): Int {
        val now = System.currentTimeMillis()
        if (maybeApplyHistoryBootstrap(plant, now)) return currentInterval

        val recentFeedback = careLogRepository.getRecentWaterings(plantId, limit = RECENT_WATERINGS_WINDOW)
            .map { it.wateringFeedback }
        val currentBase = currentAdaptiveBaseIntervalDays(plant, currentInterval)
        val frozen = WateringLifecycleReset.isFrozen(plant.wateringFreezeUntil, now)
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = feedback,
            observedIntervalDays = deseasonalizedObservedIntervalDays(actualIntervalDays, plant.pinIntervalToBase),
            currentBaseIntervalDays = currentBase,
            currentConfidence = plant.wateringConfidence,
            recentFeedback = recentFeedback,
            frozen = frozen
        )
        if (result.confidence != plant.wateringConfidence) {
            plantRepository.updatePlant(plant.copy(wateringConfidence = result.confidence, updatedAt = now))
        }
        wateringAdjustmentRepository?.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = now,
                trigger = adjustmentTriggerFor(feedback, result.excludedFromBaseLearning, frozen),
                beforeIntervalDays = currentBase,
                afterIntervalDays = result.intervalDays
            )
        )
        return result.intervalDays
    }

    /**
     * See [com.yapt.planttracker.domain.usecase.QuickLogUseCase]'s copy of this same helper — identical
     * rule, duplicated per this file's convention.
     */
    private suspend fun maybeApplyHistoryBootstrap(plant: Plant, now: Long): Boolean {
        val boundaryMs = when {
            plant.wateringConfidence == null -> Long.MIN_VALUE
            plant.wateringResetAt != null -> plant.wateringFreezeUntil ?: plant.wateringResetAt
            else -> return false
        }
        val request = WateringLifecycleReset.BootstrapRequest(
            plant = plant,
            waterLogTimestampsMs = careLogRepository.getWaterLogTimestampsAscending(plantId),
            boundaryMs = boundaryMs,
            seasonFn = seasonFnFor(plant)
        )
        return WateringLifecycleReset.maybeBootstrap(request, plantRepository, wateringAdjustmentRepository, now)
    }

    /** See [com.yapt.planttracker.domain.usecase.QuickLogUseCase]'s copy of this same helper. `null` [dataStore] behaves like amplitude 0.0. */
    @Suppress("ReturnCount")
    private suspend fun seasonFnFor(plant: Plant): (LocalDate) -> Double {
        if (plant.pinIntervalToBase) return { 1.0 }
        val store = dataStore ?: return { 1.0 }
        val amplitude = store.seasonalAmplitudeOnce()
        if (amplitude == 0.0) return { 1.0 }
        val hemisphere = SeasonalWatering.currentHemisphere()
        return { date -> SeasonalWatering.season(date, amplitude, hemisphere) }
    }

    private fun adjustmentTriggerFor(
        feedback: WateringFeedback?,
        excludedFromBaseLearning: Boolean,
        frozen: Boolean = false
    ): WateringAdjustmentTrigger = when {
        frozen -> WateringAdjustmentTrigger.FROZEN_POST_REPOT
        excludedFromBaseLearning -> WateringAdjustmentTrigger.WATER_NOT_ATTRIBUTED
        feedback == WateringFeedback.TOO_SOON -> WateringAdjustmentTrigger.WATER_TOO_SOON
        feedback == WateringFeedback.TOO_LATE -> WateringAdjustmentTrigger.WATER_TOO_LATE
        feedback == WateringFeedback.JUST_RIGHT -> WateringAdjustmentTrigger.WATER_JUST_RIGHT
        else -> WateringAdjustmentTrigger.WATER_NEUTRAL
    }

    private suspend fun isAdaptiveWateringEnabled(): Boolean {
        val store = dataStore ?: return false
        return store.data.first()[FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING)]
            ?: FeatureFlagRegistry.ADAPTIVE_WATERING.default
    }

    /**
     * Season-neutral `currentBaseIntervalDays` input (#572, amending technical ADR-0021) — mirrors
     * [com.yapt.planttracker.domain.usecase.QuickLogUseCase]'s private copy of the same helper.
     */
    @Suppress("ReturnCount")
    private suspend fun currentAdaptiveBaseIntervalDays(plant: Plant, configuredIntervalDays: Int): Int {
        if (plant.pinIntervalToBase) return configuredIntervalDays
        val store = dataStore ?: return configuredIntervalDays
        val amplitude = store.seasonalAmplitudeOnce()
        if (amplitude == 0.0) return configuredIntervalDays
        return (plant.wateringBaseIntervalDays ?: configuredIntervalDays.toDouble()).roundToInt()
    }

    /**
     * "Interaction with Part 1" (#569): `observedBase = observedGap / season(dateOfGap)`, so a
     * July correction isn't baked into [Plant.wateringConfidence] as "this plant is permanently
     * thirsty" once the seasonal curve is accounted for. A no-op ([actualIntervalDays] unchanged)
     * when [dataStore] is null, SEASONAL_WATERING is off, or [pinIntervalToBase] is set — [CareSchedule]'s
     * due-date math never applies the seasonal curve for a pinned plant, so its observed gaps are
     * already flat and must not be seasonally corrected.
     */
    @Suppress("ReturnCount")
    private suspend fun deseasonalizedObservedIntervalDays(actualIntervalDays: Int, pinIntervalToBase: Boolean): Int {
        if (pinIntervalToBase) return actualIntervalDays
        val store = dataStore ?: return actualIntervalDays
        val amplitude = store.seasonalAmplitudeOnce()
        return if (amplitude == 0.0) {
            actualIntervalDays
        } else {
            SeasonalWatering.deseasonalizeToDays(
                actualIntervalDays,
                loggedAt.toLocalDate(),
                amplitude,
                SeasonalWatering.currentHemisphere()
            )
        }
    }

    sealed class Event {
        data class Saved(val suggestedWateringInterval: Int?) : Event()
        data object NavigateBack : Event()
    }

    class Factory(
        private val careLogRepository: CareLogRepository,
        private val plantRepository: PlantRepository,
        private val plantId: Long,
        private val careLogId: Long = 0L,
        private val dataStore: DataStore<Preferences>? = null,
        private val wateringAdjustmentRepository: WateringAdjustmentRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AddCareLogViewModel(
                careLogRepository,
                plantRepository,
                plantId,
                careLogId,
                dataStore,
                wateringAdjustmentRepository
            ) as T
    }

    private companion object {
        const val RECENT_WATERINGS_WINDOW = 3
    }
}
