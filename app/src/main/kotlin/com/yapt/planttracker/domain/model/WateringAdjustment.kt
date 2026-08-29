package com.yapt.planttracker.domain.model

/**
 * A record of one adaptive-watering-model evaluation for a plant (#572) — written every time
 * [com.yapt.planttracker.domain.schedule.CareSchedule.computeAdaptiveInterval] is evaluated while
 * `ADAPTIVE_WATERING` is on, including a no-op observation where [beforeIntervalDays] equals
 * [afterIntervalDays] (still evidence the model considered). Backs the "Recent adjustments" list on
 * the "Why this date?" sheet.
 *
 * A dedicated table rather than a `CareLog` replay (product ADR-0028): a dialog dismissal, an
 * inline/AddEditPlant manual edit, and a silently-applied suggestion all change
 * [com.yapt.planttracker.domain.model.Plant.wateringConfidence]/base without ever writing a
 * `CareLog` row, so a pure replay would misrepresent history.
 */
data class WateringAdjustment(
    val id: Long = 0,
    val plantId: Long,
    val triggeredAt: Long = System.currentTimeMillis(),
    val trigger: WateringAdjustmentTrigger,
    val beforeIntervalDays: Int,
    val afterIntervalDays: Int
)

/** What caused a [WateringAdjustment] row to be written. See [WateringAdjustment]'s doc for context. */
enum class WateringAdjustmentTrigger {
    WATER_TOO_SOON,
    WATER_TOO_LATE,
    WATER_JUST_RIGHT,
    WATER_NEUTRAL,

    /**
     * An off-schedule watering the user was asked about and declined to attribute to the plant
     * ("Just my timing", or logging without choosing a reason) — #586, product ADR-0030. Distinct
     * from [WATER_NEUTRAL] (an on-schedule watering, never prompted) so "Recent adjustments" can say
     * the model deliberately ignored an observation rather than merely finding nothing to change.
     */
    WATER_NOT_ATTRIBUTED,
    CHECK_STILL_MOIST,
    DIALOG_DISMISSAL,
    DIALOG_EDIT,
    MANUAL_EDIT,

    /**
     * The Snackbar "Undo" action on a silently-applied suggestion (#584 review) —
     * [com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel.undoSilentIntervalApply],
     * deliberately distinct from [DIALOG_EDIT] so "Recent adjustments" never shows a reverted apply
     * as if it still stood.
     */
    SILENT_APPLY_UNDONE
}
