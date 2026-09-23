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
    val rescheduleDeltaDays: Int? = null,
    /**
     * The schedule-computed watering due date **before** [Plant.wateringDueDateOverride] is applied
     * (#720) — distinct from [nextWateringDueAt], which is the post-`maxOf()` effective date. Exists
     * so the Reschedule dialog's custom-date picker can tell which candidate dates `maxOf()` will
     * discard: an override earlier than this value never wins and is silently dropped, so the picker
     * must reject it rather than let the tap succeed with no visible effect. Computed once inside
     * [com.yapt.planttracker.domain.schedule.CareSchedule.computeWateringDue] — never re-derived in
     * the UI layer, same "no drift by construction" posture [rescheduleDeltaDays] already documents.
     * `null` whenever there is no watering interval configured. Defaulted so a status built by hand in
     * a test is unaffected.
     */
    val computedNextWateringDueAt: Long? = null,
    /**
     * Whether [plant]'s dormancy window (#699/#760, product ADR-0044) contains the current month —
     * `true` forces [isOverdue]/[isDueSoon] both `false` for watering purposes, regardless of how far
     * past [nextWateringDueAt] the plant is. Deliberately **not** a due-date push to the window's
     * end: [nextWateringDueAt] is computed exactly as today and may legitimately land inside the
     * window. Computed once inside
     * [com.yapt.planttracker.domain.schedule.CareSchedule.computeStatus] via
     * [com.yapt.planttracker.domain.schedule.DormancyWindow.isDormant] — never re-derived at a call
     * site. Defaulted `false` so a status built by hand in a test is unaffected.
     */
    val isDormant: Boolean = false,
    /**
     * Whether the gap since [lastWateredAt] (or, with no prior watering, nothing to gate) overlaps
     * [plant]'s dormancy window at all — a **distinct**, related condition from [isDormant] (#761,
     * product ADR-0044). [isDormant] asks "is the current month dormant?"; this asks "did dormancy
     * happen anywhere between the last watering and now?", via
     * [com.yapt.planttracker.domain.schedule.DormancyWindow.spansDormancy]. Every quick-log surface's
     * reason-prompt gate (`isWateringOnSchedule || isWateringGapDormancySpanning`) suppresses the
     * "why was it late?" prompt when this is `true` — the question is incoherent for a plant that was
     * asleep, and answering it risks writing a [CareLog.wateringFeedback] that would otherwise poison
     * a future [com.yapt.planttracker.domain.schedule.CareSchedule.correctionStreak] window even
     * though the observation itself is excluded from base learning. `false` with no prior watering,
     * matching [isWateringOnSchedule]'s own "nothing to gate" convention. Defaulted `false` so a
     * status built by hand in a test is unaffected.
     */
    val isWateringGapDormancySpanning: Boolean = false,
    /** Which watering schedule is active at status-computation time (#785, product ADR-0046). */
    val wateringScheduleMode: WateringScheduleMode = WateringScheduleMode.NORMAL,
    /** Ordinary seasonal/adaptive due date before an override, even when dormancy selects another mode. */
    val normalComputedNextWateringDueAt: Long? = null,
    /** Fixed-cadence dormant due date before an override; populated only in [WateringScheduleMode.DORMANT_CADENCE]. */
    val dormantComputedNextWateringDueAt: Long? = null
)

enum class WateringScheduleMode { NORMAL, DORMANT_CADENCE, DORMANT_SUSPENDED }
