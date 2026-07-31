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
    data class MistingOverdue(val days: Int) : CareReminderItem()
    data object MistingDueToday : CareReminderItem()
    data class RepottingOverdue(val days: Int) : CareReminderItem()
    data object RepottingDueToday : CareReminderItem()
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

        if (status.isMistingOverdue) {
            val days = ChronoUnit.DAYS.between(status.nextMistingDueAt!!.toLocalDate(), nowDate).toInt()
            items.add(CareReminderItem.MistingOverdue(days))
        } else if (status.isMistingDueSoon) {
            items.add(CareReminderItem.MistingDueToday)
        }

        if (status.isRepottingOverdue) {
            val days = ChronoUnit.DAYS.between(status.nextRepottingDueAt!!.toLocalDate(), nowDate).toInt()
            items.add(CareReminderItem.RepottingOverdue(days))
        } else if (status.isRepottingDueSoon) {
            items.add(CareReminderItem.RepottingDueToday)
        }

        return items
    }

    /**
     * @param fertilizingNotificationsEnabled when `false`, a plant whose only due care is
     *   fertilizing (no watering item) is dropped — a fertilize being due is not urgent enough to
     *   notify on its own (#223). Plants that are also watering-due keep their full body, including
     *   the fertilizing line, because the watering urgency makes the reminder timely regardless.
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
                !fertilizingNotificationsEnabled && !items.hasWateringItem() -> null
                else -> DuePlantReminder(status, items)
            }
        }

    private fun List<CareReminderItem>.hasWateringItem(): Boolean =
        any { it is CareReminderItem.WateringOverdue || it is CareReminderItem.WateringDueToday }
}
