package com.yapt.planttracker.domain.model

/**
 * Why an off-schedule watering happened (#586, product ADR-0030; late-direction mapping amended by
 * #649 product ADR-0032). The app **asks** rather than infers: the same observable gap carries
 * opposite meanings — watered three days late because the plant was fine and could go longer, or
 * because you were busy and it went thirsty — so timing alone can never tell *the plant's needs*
 * from *the user's availability*, the exact conflation ADR-0007 exists to prevent.
 *
 * `null` at a call site means no reason was given: either the watering was on schedule (no prompt
 * appears at all) or the user logged without choosing. Both mean the same thing to the model — no
 * attribution. What separates them is timing, via
 * [com.yapt.planttracker.domain.model.PlantCareStatus.isWateringOnSchedule], so the fifth state this
 * design needs never has to be persisted in the four-state
 * [CareLog.wateringFeedback] column (see [com.yapt.planttracker.domain.schedule.CareSchedule
 * .computeAdaptiveInterval]).
 *
 * The three values are **not** interchangeable across direction: [PLANT_NEEDED_IT] is offered only
 * on the early side ("Why now?") and [SOIL_STILL_MOIST] only on the late side ("Why was it late?") —
 * see [com.yapt.planttracker.ui.components.WateringReasonBottomSheet], which picks the option set by
 * [com.yapt.planttracker.domain.model.PlantCareStatus.isWateringGapLong]. [JUST_MY_TIMING] is the
 * only value common to both.
 */
enum class WateringReason {
    /**
     * "The plant needed it" — early-direction only. Evidence about the plant, so it feeds the
     * adaptive model as [WateringFeedback.TOO_LATE], shortening the interval: watering *before* the
     * scheduled date because it was already dry is a precise signal about exactly when the plant
     * needed water.
     */
    PLANT_NEEDED_IT,

    /**
     * "Soil was still moist" — late-direction only (#649, product ADR-0032). Evidence about the
     * plant, feeding the model as [WateringFeedback.TOO_SOON] — the same signal
     * [RescheduleReason.SOIL_STILL_MOIST] represents, just captured retroactively on the WATER log
     * for a user who checked informally rather than tapping Reschedule. A late gap **never** shortens
     * the interval (that would require knowing exactly when inside the overdue window the plant went
     * dry, which a single retrospective observation cannot): the late direction only ever holds
     * ([JUST_MY_TIMING], excluded) or lengthens (this value).
     */
    SOIL_STILL_MOIST,

    /** "Just my timing" (or "Forgot, or no time") — about the user's availability, either direction, so the model must ignore it entirely. */
    JUST_MY_TIMING;

    /**
     * The [CareLog.wateringFeedback] stored for this reason. [JUST_MY_TIMING] stores nothing: it is
     * the absence of evidence, and reusing the existing nullable column is what keeps this issue
     * schema-free.
     *
     * [SOIL_STILL_MOIST] maps to [WateringFeedback.TOO_SOON] (#649, product ADR-0032, amending
     * ADR-0030's "TOO_SOON becomes structurally impossible on a WATER log" — that no longer holds:
     * TOO_SOON is now reachable on a WATER log via this value, not only via [RescheduleReason
     * .SOIL_STILL_MOIST]'s `CareType.CHECK` log).
     */
    fun toWateringFeedback(): WateringFeedback? = when (this) {
        PLANT_NEEDED_IT -> WateringFeedback.TOO_LATE
        SOIL_STILL_MOIST -> WateringFeedback.TOO_SOON
        JUST_MY_TIMING -> null
    }
}

/**
 * Why a watering was rescheduled (#586, product ADR-0030) — the symmetric half of [WateringReason],
 * and the reason Reschedule is no longer unconditionally inert to the adaptive model (superseding
 * ADR-0029). Rescheduling +2 days because the soil is still wet and rescheduling +2 days because you
 * are away for the weekend are the same calendar operation and opposite observations; the answer,
 * never the duration, decides which one the model sees.
 */
enum class RescheduleReason {
    /**
     * "Soil still moist" — an observation about the plant. Writes the same `CareType.CHECK` log
     * (`wateringFeedback = TOO_SOON`) the notification's Still-moist action does, through the same
     * [com.yapt.planttracker.domain.usecase.QuickLogUseCase.recordStillMoistCheck] call site.
     */
    SOIL_STILL_MOIST,

    /**
     * "I can't right now" — about the user. A pure `wateringDueDateOverride` write with no log and
     * no model effect, exactly as every Reschedule option behaved under ADR-0029.
     */
    CANT_RIGHT_NOW
}
