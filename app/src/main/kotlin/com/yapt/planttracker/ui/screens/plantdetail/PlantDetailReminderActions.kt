package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.PlantIssue
import kotlinx.coroutines.launch

fun PlantDetailViewModel.addCustomReminder(name: String, intervalDays: Int) {
viewModelScope.launch {
    customReminderRepository.addReminder(
        CustomReminder(plantId = plantId, name = name, intervalDays = intervalDays)
    )
}
}

fun PlantDetailViewModel.updateCustomReminder(reminder: CustomReminder, name: String, intervalDays: Int) {
viewModelScope.launch {
    customReminderRepository.updateReminder(reminder.copy(name = name, intervalDays = intervalDays))
}
}

fun PlantDetailViewModel.deleteCustomReminder(reminder: CustomReminder) {
viewModelScope.launch { customReminderRepository.deleteReminder(reminder) }
}

fun PlantDetailViewModel.markCustomReminderDone(reminder: CustomReminder) {
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

fun PlantDetailViewModel.reportIssue(name: String, reminderName: String?, reminderIntervalDays: Int?) {
viewModelScope.launch {
    database.withTransaction {
        val reminderId = if (reminderName != null && reminderIntervalDays != null) customReminderRepository.addReminder(
            CustomReminder(plantId = plantId, name = reminderName, intervalDays = reminderIntervalDays)
        ) else null
        plantIssueRepository.addIssue(PlantIssue(plantId = plantId, name = name, linkedReminderId = reminderId))
    }
}
}

fun PlantDetailViewModel.resolveIssue(issue: PlantIssue, resolutionNote: String?) {
viewModelScope.launch {
    plantIssueRepository.updateIssue(issue.copy(resolvedAt = System.currentTimeMillis(), resolutionNote = resolutionNote?.takeIf {
        it.isNotBlank()
    }))
}
}
