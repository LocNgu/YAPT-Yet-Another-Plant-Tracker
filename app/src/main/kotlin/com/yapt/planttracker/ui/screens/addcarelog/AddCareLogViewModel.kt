package com.yapt.planttracker.ui.screens.addcarelog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.schedule.CareSchedule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AddCareLogViewModel(
    private val careLogRepository: CareLogRepository,
    private val plantRepository: PlantRepository,
    private val plantId: Long,
    private val careLogId: Long = 0L
) : ViewModel() {

    val isEditMode = careLogId != 0L

    var selectedCareType by mutableStateOf(CareType.WATER)
    var notes by mutableStateOf("")
    var photoUri by mutableStateOf<String?>(null)
    var amount by mutableStateOf("")
    var loggedAt by mutableStateOf(System.currentTimeMillis())
    var selectedFeedback by mutableStateOf<WateringFeedback?>(null)

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events

    init {
        if (isEditMode) {
            viewModelScope.launch {
                val log = careLogRepository.getLogById(careLogId) ?: return@launch
                selectedCareType = log.careType
                notes = log.notes ?: ""
                amount = log.amount ?: ""
                photoUri = log.photoUri
                selectedFeedback = log.wateringFeedback
                loggedAt = log.loggedAt
            }
        }
    }

    fun saveLog() {
        viewModelScope.launch {
            val log = CareLog(
                id = careLogId,
                plantId = plantId,
                careType = selectedCareType,
                loggedAt = loggedAt,
                notes = notes.trim().ifBlank { null },
                photoUri = photoUri,
                amount = amount.trim().ifBlank { null },
                wateringFeedback = if (selectedCareType == CareType.WATER) selectedFeedback else null
            )
            careLogRepository.addLog(log)

            val suggestedInterval = if (isEditMode) null else computeSuggestedInterval()
            _events.emit(Event.Saved(suggestedInterval))
        }
    }

    private suspend fun computeSuggestedInterval(): Int? {
        val feedback = selectedFeedback ?: return null
        if (selectedCareType != CareType.WATER) return null

        val plant = plantRepository.getPlantById(plantId).first() ?: return null
        val currentInterval = plant.wateringIntervalDays

        val lastTwoWaterings = careLogRepository.getLastTwoWaterings(plantId)
        val actualIntervalDays = if (lastTwoWaterings.size >= 2) {
            CareSchedule.daysBetween(lastTwoWaterings[1].loggedAt, lastTwoWaterings[0].loggedAt)
        } else {
            currentInterval ?: return null
        }

        if (actualIntervalDays <= 0) return null
        val suggested = CareSchedule.computeSuggestedInterval(feedback, actualIntervalDays)
        return if (suggested != currentInterval) suggested else null
    }

    sealed class Event {
        data class Saved(val suggestedWateringInterval: Int?) : Event()
    }

    class Factory(
        private val careLogRepository: CareLogRepository,
        private val plantRepository: PlantRepository,
        private val plantId: Long,
        private val careLogId: Long = 0L
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AddCareLogViewModel(careLogRepository, plantRepository, plantId, careLogId) as T
    }
}
