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
     * Whether watering this plant **right now** would land within
     * [com.yapt.planttracker.domain.schedule.CareSchedule.GAP_AGREEMENT_TOLERANCE] of the schedule
     * (#586, product ADR-0030) — the single question every watering surface asks to decide whether to
     * prompt for a reason. `true` (no prompt) whenever there is nothing to be off-schedule against:
     * no interval configured, or no previous watering. Defaulted so a status built by hand in a test
     * keeps the quiet, no-prompt path.
     */
    val isWateringOnSchedule: Boolean = true,
    /**
     * Count of currently-unresolved [PlantIssue]s on this plant (issue #564) — a passive count, not
     * a due-date status like the other fields, so it's populated directly by each ViewModel rather
     * than routed through [com.yapt.planttracker.domain.schedule.CareSchedule.computeStatus].
     */
    val activeIssueCount: Int = 0
)
