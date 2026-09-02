package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.PlantIssue
import kotlinx.coroutines.launch

/** Adds a new custom reminder (#232) — free-text [name] plus a plain-days [intervalDays]. */
fun PlantDetailViewModel.addCustomReminder(name: String, intervalDays: Int) {
    viewModelScope.launch {
        customReminderRepository.addReminder(
            CustomReminder(plantId = plantId, name = name, intervalDays = intervalDays)
        )
    }
}

/** Renames/re-intervals an existing custom reminder without touching its [CustomReminder.lastDoneAt]. */
fun PlantDetailViewModel.updateCustomReminder(reminder: CustomReminder, name: String, intervalDays: Int) {
    viewModelScope.launch {
        customReminderRepository.updateReminder(reminder.copy(name = name, intervalDays = intervalDays))
    }
}

fun PlantDetailViewModel.deleteCustomReminder(reminder: CustomReminder) {
    viewModelScope.launch { customReminderRepository.deleteReminder(reminder) }
}

/**
 * Marks a custom reminder done: writes a [CareType.CUSTOM] [CareLog] linked back to it (visible
 * in the journal) and resets its [CustomReminder.lastDoneAt], mirroring how logging a built-in
 * care type resets its schedule (#232).
 */
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

/**
 * Reports a new plant issue (#564). When [reminderName] and [reminderIntervalDays] are both
 * non-null (the optional "set a treatment reminder" sub-section was filled in), a [CustomReminder]
 * is created first and its id stored on [PlantIssue.linkedReminderId] — a one-way, unenforced
 * link (see technical ADR-0019's `CareLog.customReminderId` precedent): resolving or deleting
 * this issue never touches the linked reminder, which keeps running independently. Both writes
 * run inside a single database transaction (mirroring `QuickLogUseCase`'s paired-write precedent)
 * so a killed process or DB error between the two inserts can never leave an orphan
 * [CustomReminder] with no [PlantIssue] pointing at it.
 */
fun PlantDetailViewModel.reportIssue(name: String, reminderName: String?, reminderIntervalDays: Int?) {
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
fun PlantDetailViewModel.resolveIssue(issue: PlantIssue, resolutionNote: String?) {
    viewModelScope.launch {
        plantIssueRepository.updateIssue(
            issue.copy(
                resolvedAt = System.currentTimeMillis(),
                resolutionNote = resolutionNote?.takeIf { it.isNotBlank() }
            )
        )
    }
}
