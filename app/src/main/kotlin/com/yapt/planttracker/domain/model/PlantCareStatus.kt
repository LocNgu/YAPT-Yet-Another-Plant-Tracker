package com.yapt.planttracker.domain.model

data class PlantCareStatus(
    val plant: Plant,
    val lastWateredAt: Long?,
    val lastFertilizedAt: Long?,
    val daysSinceLastWatering: Long?,
    val nextWateringDueAt: Long?,
    val isOverdue: Boolean,
    val isDueSoon: Boolean,
    val nextFertilizingDueAt: Long?,
    val isFertilizingOverdue: Boolean,
    val isFertilizingDueSoon: Boolean,
    val totalCareLogs: Int,
    val lastRepottedAt: Long? = null,
    val nextRepottingDueAt: Long? = null,
    val isRepottingOverdue: Boolean = false,
    val isRepottingDueSoon: Boolean = false,
    val customReminderStatuses: List<CustomReminderStatus> = emptyList(),
    /**
     * Count of currently-unresolved [PlantIssue]s on this plant (issue #564) — a passive count, not
     * a due-date status like the other fields, so it's populated directly by each ViewModel rather
     * than routed through [com.yapt.planttracker.domain.schedule.CareSchedule.computeStatus].
     */
    val activeIssueCount: Int = 0
)
