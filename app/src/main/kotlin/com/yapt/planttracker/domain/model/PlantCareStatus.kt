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
     * Which side of the schedule an off-schedule watering falls on: `true` when the gap since the
     * last watering has already run **longer** than the effective interval (watered late), `false`
     * when it is still short of it (watered early). Only meaningful while [isWateringOnSchedule] is
     * `false` — it is what lets the reason prompt ask "why was it late?" instead of "why now?",
     * which reads as an accusation on an overdue plant (#586, product ADR-0030).
     *
     * Derived from the same gap-vs-effective-interval comparison as [isWateringOnSchedule], **not**
     * from [isOverdue]: the latter is measured against the due date, which an active
     * `wateringDueDateOverride` moves, so a deferred plant can be not-overdue while its gap has
     * still run long. Defaulted `false` so a status built by hand in a test reads as the early case.
     */
    val isWateringGapLong: Boolean = false,
    /**
     * Count of currently-unresolved [PlantIssue]s on this plant (issue #564) — a passive count, not
     * a due-date status like the other fields, so it's populated directly by each ViewModel rather
     * than routed through [com.yapt.planttracker.domain.schedule.CareSchedule.computeStatus].
     */
    val activeIssueCount: Int = 0,
    /**
     * How many days [Plant.wateringDueDateOverride] has pushed the due date out beyond what the
     * schedule alone would compute (#630) — non-null only when the override is the actual `maxOf()`
     * winner (`wateringDueDateOverride != null && wateringDueDateOverride > computedNextDueAt`), so
     * the chip/sheet row self-hides once the schedule catches up and exceeds a stale override.
     * Computed once inside [com.yapt.planttracker.domain.schedule.CareSchedule.computeWateringDue] —
     * never re-derived in the UI layer, same "no drift by construction" posture as the rest of this
     * status.
     */
    val rescheduleDeltaDays: Int? = null
)
