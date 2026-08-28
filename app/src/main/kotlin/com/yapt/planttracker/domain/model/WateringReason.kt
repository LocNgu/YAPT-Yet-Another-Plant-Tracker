package com.yapt.planttracker.domain.model

/**
 * Why an off-schedule watering happened (#586, product ADR-0030). The app **asks** rather than
 * infers: the same observable gap carries opposite meanings — watered three days late because the
 * plant was fine and could go longer, or because you were busy and it went thirsty — so timing alone
 * can never tell *the plant's needs* from *the user's availability*, the exact conflation ADR-0007
 * exists to prevent.
 *
 * `null` at a call site means no reason was given: either the watering was on schedule (no prompt
 * appears at all) or the user logged without choosing. Both mean the same thing to the model — no
 * attribution. What separates them is timing, via
 * [com.yapt.planttracker.domain.model.PlantCareStatus.isWateringOnSchedule], so the fifth state this
 * design needs never has to be persisted in the four-state
 * [CareLog.wateringFeedback] column (see [com.yapt.planttracker.domain.schedule.CareSchedule
 * .computeAdaptiveInterval]).
 */
enum class WateringReason {
    /** "The plant needed it" — evidence about the plant, so it feeds the adaptive model. */
    PLANT_NEEDED_IT,

    /** "Just my timing" — about the user's availability, so the model must ignore it entirely. */
    JUST_MY_TIMING;

    /**
     * The [CareLog.wateringFeedback] stored for this reason. [PLANT_NEEDED_IT] maps to
     * [WateringFeedback.TOO_LATE] in **both** directions — #568's multiplier applies to the *observed
     * gap*, not to the base interval, so one value correctly shortens after an early watering and
     * pulls back after an over-long one. [JUST_MY_TIMING] stores nothing: it is the absence of
     * evidence, and reusing the existing nullable column is what keeps this issue schema-free.
     *
     * There is deliberately no reason that maps to [WateringFeedback.TOO_SOON]: "the soil was still
     * wet" is not something a *watering* can observe, so TOO_SOON on a WATER log stops being a
     * convention and becomes unrepresentable — it is now reachable only from [RescheduleReason
     * .SOIL_STILL_MOIST]'s `CareType.CHECK` log.
     */
    fun toWateringFeedback(): WateringFeedback? = when (this) {
        PLANT_NEEDED_IT -> WateringFeedback.TOO_LATE
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
