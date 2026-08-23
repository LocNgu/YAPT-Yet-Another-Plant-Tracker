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
    CHECK_STILL_MOIST,
    DIALOG_DISMISSAL,
    DIALOG_EDIT,
    MANUAL_EDIT
}
