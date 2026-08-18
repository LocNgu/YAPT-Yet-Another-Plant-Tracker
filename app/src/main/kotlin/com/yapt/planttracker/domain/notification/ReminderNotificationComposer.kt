package com.yapt.planttracker.domain.notification

import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.util.toLocalDate
import java.time.temporal.ChronoUnit

sealed class CareReminderItem {
    data class WateringOverdue(val days: Int) : CareReminderItem()
    data object WateringDueToday : CareReminderItem()
    data class FertilizingOverdue(val days: Int) : CareReminderItem()
    data object FertilizingDueToday : CareReminderItem()
    data object FertilizeWithWatering : CareReminderItem()
    data class RepottingOverdue(val days: Int) : CareReminderItem()
    data object RepottingDueToday : CareReminderItem()
    data class CustomReminderOverdue(val name: String, val days: Int) : CareReminderItem()
    data class CustomReminderDueToday(val name: String) : CareReminderItem()
}

data class DuePlantReminder(val status: PlantCareStatus, val items: List<CareReminderItem>)

/**
 * Pure, JVM-testable composition of daily care reminders. No Android Context dependency —
 * [ReminderWorker][com.yapt.planttracker.worker.ReminderWorker] turns [CareReminderItem]s into
 * localized strings and posts the actual notifications.
 */
object ReminderNotificationComposer {

    fun computeCareReminderItems(status: PlantCareStatus, now: Long): List<CareReminderItem> {
        val items = mutableListOf<CareReminderItem>()
        val nowDate = now.toLocalDate()

        if (status.isOverdue) {
            val days = ChronoUnit.DAYS.between(status.nextWateringDueAt!!.toLocalDate(), nowDate).toInt()
            items.add(CareReminderItem.WateringOverdue(days))
        } else if (status.isDueSoon) {
            items.add(CareReminderItem.WateringDueToday)
        }

        if (status.isFertilizingOverdue || status.isFertilizingDueSoon) {
            if (status.plant.useLiquidFertilizer) {
                if (items.isNotEmpty()) items.add(CareReminderItem.FertilizeWithWatering)
            } else if (status.isFertilizingOverdue) {
                val days = ChronoUnit.DAYS.between(status.nextFertilizingDueAt!!.toLocalDate(), nowDate).toInt()
                items.add(CareReminderItem.FertilizingOverdue(days))
            } else {
                items.add(CareReminderItem.FertilizingDueToday)
            }
        }

        if (status.isRepottingOverdue) {
            val days = ChronoUnit.DAYS.between(status.nextRepottingDueAt!!.toLocalDate(), nowDate).toInt()
            items.add(CareReminderItem.RepottingOverdue(days))
        } else if (status.isRepottingDueSoon) {
            items.add(CareReminderItem.RepottingDueToday)
        }

        for (reminderStatus in status.customReminderStatuses) {
            if (reminderStatus.isOverdue) {
                val days = ChronoUnit.DAYS.between(reminderStatus.nextDueAt!!.toLocalDate(), nowDate).toInt()
                items.add(CareReminderItem.CustomReminderOverdue(reminderStatus.reminder.name, days))
            } else if (reminderStatus.isDueSoon) {
                items.add(CareReminderItem.CustomReminderDueToday(reminderStatus.reminder.name))
            }
        }

        return items
    }

    /**
     * @param fertilizingNotificationsEnabled when `false`, a plant whose due care is *only*
     *   fertilizing is dropped — a fertilize being due is not urgent enough to notify on its own
     *   (#223). A plant that also has any non-fertilizing item (watering, repotting) keeps its full
     *   body, including the fertilizing line, because that other care makes the reminder timely
     *   regardless. Note this deliberately tests "every item is a fertilizing item" rather than
     *   "has no watering item": those were equivalent when watering and fertilizing were the only
     *   reminder types, but repotting (#232) made them differ, and the latter would have silently
     *   suppressed repotting-only reminders.
     */
    fun computeDueReminders(
        statuses: List<PlantCareStatus>,
        now: Long,
        fertilizingNotificationsEnabled: Boolean = true
    ): List<DuePlantReminder> =
        statuses.mapNotNull { status ->
            val items = computeCareReminderItems(status, now)
            when {
                items.isEmpty() -> null
                !fertilizingNotificationsEnabled && items.isFertilizingOnly() -> null
                else -> DuePlantReminder(status, items)
            }
        }

    /**
     * True when every composed item is a fertilizing item, i.e. fertilizing is the *only* reason
     * this plant would be notified. `FertilizeWithWatering` is only ever added alongside a watering
     * item, so it cannot make a list fertilizing-only on its own.
     */
    private fun List<CareReminderItem>.isFertilizingOnly(): Boolean = all {
        it is CareReminderItem.FertilizingOverdue ||
            it is CareReminderItem.FertilizingDueToday ||
            it is CareReminderItem.FertilizeWithWatering
    }
}
