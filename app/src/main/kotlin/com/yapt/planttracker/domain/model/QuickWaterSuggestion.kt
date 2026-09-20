package com.yapt.planttracker.domain.model

/**
 * Carries a watering-interval suggestion from a quick-water action back to the calling screen.
 * [suggestedInterval] is the model's raw, season-neutral **base**-space value — `QuickLogUseCase
 * .applyWateringIntervalSuggestion()`'s own accounting derives [Plant.wateringBaseIntervalDays] from
 * the applied value, but no longer reads this field directly as its `newInterval` input.
 * [suggestedIntervalEffective] is the same suggestion converted to effective (display) space via
 * [com.yapt.planttracker.domain.schedule.CareSchedule.effectiveWateringIntervalDaysForDisplay] — the
 * product ADR-0006 suggestion dialog's body text, its "should this even show" gate, and (as of #644) its
 * editable field and the value it submits to Apply all use this value instead, so nothing compares a
 * base-space number against an already seasonally-adjusted "current" (#620/#644). Equal to
 * [suggestedInterval] whenever the plant is pinned or amplitude is Off.
 *
 * [suggestedBaseInterval] is the model's *unrounded* base-space result (technical ADR-0027) and is
 * deliberately **not** defaulted: defaulting it to `suggestedInterval.toDouble()` would let a
 * construction site silently substitute the rounded value for the precise one, which is the exact
 * precision loss #717/#718 removed, and it would fail silently rather than at compile time.
 */
data class QuickWaterSuggestion(
    val plantId: Long,
    val plantName: String,
    val suggestedInterval: Int,
    val suggestedIntervalEffective: Int,
    val suggestedBaseInterval: Double
)
