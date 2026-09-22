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
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.usecase.AdaptiveWateringObservation
import com.yapt.planttracker.domain.usecase.WateringLifecycleReset
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// #568 added two small adaptive-watering helpers to this VM's one cohesive save flow; splitting
// them out would scatter that flow across files for no readability gain.
@Suppress("TooManyFunctions", "LongParameterList")
class AddCareLogViewModel(
    private val careLogRepository: CareLogRepository,
    private val plantRepository: PlantRepository,
    private val plantId: Long,
    private val careLogId: Long = 0L,
    // Nullable + defaulted so the many existing tests constructing this VM directly don't all need
    // updating; null is treated the same as amplitude being Off (#569).
    private val dataStore: DataStore<Preferences>? = null,
    // Nullable + defaulted for the same reason as [dataStore] — `?.addAdjustment` calls below are
    // safe no-ops for tests that don't pass one (#572).
    private val wateringAdjustmentRepository: WateringAdjustmentRepository? = null,
    private val onWaterLogged: suspend (Long) -> Unit = {}
) : ViewModel() {

    private val adaptiveObservation = AdaptiveWateringObservation(
        plantRepository,
        careLogRepository,
        dataStore,
        wateringAdjustmentRepository
    )

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
    private var initialCareTypeApplied = false

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

    /**
     * Applies a navigation-requested care type once when creating a new log (#658). The one-shot
     * guard preserves a user's later chip selection if the screen composition is recreated.
     */
    fun preselectCareType(careType: CareType) {
        if (!isEditMode && !initialCareTypeApplied) selectedCareType = careType
        initialCareTypeApplied = true
    }

    fun saveLog() {
        if (selectedCareType == CareType.PHOTO && photoUri == null) return
        viewModelScope.launch {
            if (isDuplicateLog()) return@launch
            duplicateLogError = null

            // Checked before the FERTILIZE insert below so it can't race against a paired WATER
            // row inserted by this same save (#509).
            val willPairWater = shouldPairWaterLog()

            careLogRepository.addLog(buildLogFromState(feedbackForLog()))

            if (!isEditMode && selectedCareType == CareType.REPOT) {
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

            val suggestion = computeSuggestedInterval()
            schedulePostWateringReminderIfNeeded(willPairWater)
            _events.emit(
                Event.Saved(
                    suggestedWateringInterval = suggestion?.intervalDays,
                    suggestedWateringBaseInterval = suggestion?.baseIntervalDays
                )
            )
        }
    }

    private suspend fun schedulePostWateringReminderIfNeeded(willPairWater: Boolean) {
        if (!isEditMode && (selectedCareType == CareType.WATER || willPairWater)) {
            onWaterLogged(loggedAt)
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

    private fun buildLogFromState(wateringFeedback: WateringFeedback?) = CareLog(
        id = careLogId,
        plantId = plantId,
        careType = selectedCareType,
        loggedAt = loggedAt,
        notes = notes.trim().ifBlank { null },
        photoUri = photoUri,
        amount = amount.trim().ifBlank { null },
        wateringFeedback = wateringFeedback,
        fertilizerType = if (selectedCareType == CareType.FERTILIZE) selectedFertilizerType else FertilizerType.UNSPECIFIED,
        customReminderId = customReminderId
    )

    private suspend fun insertPairedWaterLog() {
        // No reason: the user fertilized, and the watering came along with it (product ADR-0008) — they were
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

    /** Recheck every save, including edits, while excluding the row being replaced as a predecessor. */
    @Suppress("ReturnCount")
    private suspend fun feedbackForLog(): WateringFeedback? {
        if (selectedCareType != CareType.WATER) return null
        val plant = plantRepository.getPlantById(plantId).first() ?: return selectedFeedback
        val excludeId = if (isEditMode) careLogId else null
        return adaptiveObservation.feedbackForLog(plant, selectedFeedback, loggedAt, excludeId)
    }

    @StringRes
    private fun duplicateErrorRes(careType: CareType): Int = when (careType) {
        CareType.WATER -> R.string.care_log_error_already_watered
        CareType.FERTILIZE -> R.string.care_log_error_already_fertilized
        else -> error("No duplicate guard defined for $careType")
    }

    internal data class SuggestedInterval(val intervalDays: Int, val baseIntervalDays: Double)

    /** Observation time is [loggedAt]; [displayNow] is today's date for the live suggestion gate. */
    @Suppress("ReturnCount")
    internal suspend fun computeSuggestedInterval(displayNow: Long = System.currentTimeMillis()): SuggestedInterval? {
        if (selectedCareType != CareType.WATER) return null
        val plant = plantRepository.getPlantById(plantId).first() ?: return null
        val suggestion = adaptiveObservation.observe(
            plant,
            selectedFeedback,
            loggedAt,
            displayNow,
            AdaptiveWateringObservation.GapSource.NEWEST_PAIR_OR_CONFIGURED,
            isEditMode = isEditMode
        ) ?: return null
        return SuggestedInterval(suggestion.intervalDays, suggestion.baseIntervalDays)
    }

    sealed class Event {
        /**
         * [suggestedWateringBaseInterval] is the unrounded base-space value behind
         * [suggestedWateringInterval] (technical ADR-0027); both are null together when the save
         * produced no suggestion. Not defaulted, so an emit site cannot drop the precise base and
         * silently fall back to the rounded one (#717/#718).
         */
        data class Saved(
            val suggestedWateringInterval: Int?,
            val suggestedWateringBaseInterval: Double?
        ) : Event()
        data object NavigateBack : Event()
    }

    @Suppress("LongParameterList")
    class Factory(
        private val careLogRepository: CareLogRepository,
        private val plantRepository: PlantRepository,
        private val plantId: Long,
        private val careLogId: Long = 0L,
        private val dataStore: DataStore<Preferences>? = null,
        private val wateringAdjustmentRepository: WateringAdjustmentRepository? = null,
        private val onWaterLogged: suspend (Long) -> Unit = {}
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AddCareLogViewModel(
                careLogRepository,
                plantRepository,
                plantId,
                careLogId,
                dataStore,
                wateringAdjustmentRepository,
                onWaterLogged
            ) as T
    }
}
