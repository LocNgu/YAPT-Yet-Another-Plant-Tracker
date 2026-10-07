package com.yapt.planttracker.ui.screens.addcarelog

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.usecase.AdaptiveWateringObservation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Edit-only (#532, part 3): the screen opens an existing log by [careLogId] and never changes its type.
// New logs come from the in-pane actions via QuickLogUseCase.
// It never observes a WATER log or hands back an interval suggestion (technical ADR-0037); only the
// dormancy feedback strip of the shared path applies.
class AddCareLogViewModel(
    private val careLogRepository: CareLogRepository,
    private val plantRepository: PlantRepository,
    private val plantId: Long,
    private val careLogId: Long
) : ViewModel() {

    private val adaptiveObservation = AdaptiveWateringObservation(
        plantRepository,
        careLogRepository,
        dataStore = null,
        wateringAdjustmentRepository = null
    )

    // Fixed once the log loads; the screen shows it as a read-only header, so an edit can never write a
    // retired type (NOTE/MIST/CHECK) or turn one log into another.
    var careType by mutableStateOf(CareType.WATER)
        private set
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

    // false until the async load completes; used to key DatePickerState and gate the form.
    var isLoaded by mutableStateOf(false)
        private set

    // Set on a rejected same-day WATER/FERTILIZE duplicate (#509); the screen shows this inline
    // instead of a dialog or disabling Save, so the user can adjust the date and retry.
    var duplicateLogError by mutableStateOf<Int?>(null)
        private set

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events

    init {
        viewModelScope.launch {
            val log = careLogRepository.getLogById(careLogId) ?: run {
                _events.emit(Event.NavigateBack)
                return@launch
            }
            careType = log.careType
            notes = log.notes ?: ""
            amount = log.amount ?: ""
            photoUri = log.photoUri
            selectedFeedback = log.wateringFeedback
            selectedFertilizerType = log.fertilizerType
            loggedAt = log.loggedAt
            customReminderId = log.customReminderId
            isLoaded = true
        }
    }

    /** Clears a previously-shown duplicate error, e.g. once the user edits the date. */
    fun clearDuplicateLogError() {
        duplicateLogError = null
    }

    fun saveLog() {
        if (!isLoaded || (careType == CareType.PHOTO && photoUri == null)) return
        viewModelScope.launch {
            if (isDuplicateLog()) return@launch
            duplicateLogError = null

            careLogRepository.addLog(buildLogFromState(feedbackForLog()))

            if (careType == CareType.PHOTO && photoUri != null) updateCoverPhoto()

            _events.emit(Event.Saved)
        }
    }

    /** Sets [duplicateLogError] and returns true if [careType]/[loggedAt] is a same-day WATER/FERTILIZE duplicate. */
    private suspend fun isDuplicateLog(): Boolean {
        if (careType != CareType.WATER && careType != CareType.FERTILIZE) return false
        val isDuplicate = careLogRepository.hasLogOfTypeOnDay(plantId, careType, loggedAt, careLogId)
        if (isDuplicate) duplicateLogError = duplicateErrorRes(careType)
        return isDuplicate
    }

    private fun buildLogFromState(wateringFeedback: WateringFeedback?) = CareLog(
        id = careLogId,
        plantId = plantId,
        careType = careType,
        loggedAt = loggedAt,
        notes = notes.trim().ifBlank { null },
        photoUri = photoUri,
        amount = amount.trim().ifBlank { null },
        wateringFeedback = wateringFeedback,
        fertilizerType = if (careType == CareType.FERTILIZE) selectedFertilizerType else FertilizerType.UNSPECIFIED,
        customReminderId = customReminderId
    )

    private suspend fun updateCoverPhoto() {
        plantRepository.getPlantById(plantId).first()?.let { p ->
            plantRepository.updatePlant(p.copy(coverPhotoUri = photoUri, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Recheck every save while excluding the row being replaced as a predecessor. */
    @Suppress("ReturnCount")
    private suspend fun feedbackForLog(): WateringFeedback? {
        if (careType != CareType.WATER) return null
        val plant = plantRepository.getPlantById(plantId).first() ?: return selectedFeedback
        return adaptiveObservation.feedbackForLog(plant, selectedFeedback, loggedAt, careLogId)
    }

    @StringRes
    private fun duplicateErrorRes(careType: CareType): Int = when (careType) {
        CareType.WATER -> R.string.care_log_error_already_watered
        CareType.FERTILIZE -> R.string.care_log_error_already_fertilized
        else -> error("No duplicate guard defined for $careType")
    }

    sealed class Event {
        data object Saved : Event()
        data object NavigateBack : Event()
    }

    class Factory(
        private val careLogRepository: CareLogRepository,
        private val plantRepository: PlantRepository,
        private val plantId: Long,
        private val careLogId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AddCareLogViewModel(careLogRepository, plantRepository, plantId, careLogId) as T
    }
}
