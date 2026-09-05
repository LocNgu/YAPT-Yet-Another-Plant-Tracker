package com.yapt.planttracker.domain.model

/**
 * Carries a watering-interval suggestion from a quick-water action back to the calling screen.
 * [suggestedInterval] is the model's raw, season-neutral **base**-space value — `QuickLogUseCase
 * .applyWateringIntervalSuggestion()`'s own accounting derives [Plant.wateringBaseIntervalDays] from
 * the applied value, but no longer reads this field directly as its `newInterval` input.
 * [suggestedIntervalEffective] is the same suggestion converted to effective (display) space via
 * [com.yapt.planttracker.domain.schedule.CareSchedule.effectiveWateringIntervalDaysForDisplay] — the
 * ADR-0006 suggestion dialog's body text, its "should this even show" gate, and (as of #644) its
 * editable field and the value it submits to Apply all use this value instead, so nothing compares a
 * base-space number against an already seasonally-adjusted "current" (#620/#644). Equal to
 * [suggestedInterval] whenever the plant is pinned or `SEASONAL_WATERING` is off.
 */
data class QuickWaterSuggestion(
    val plantId: Long,
    val plantName: String,
    val suggestedInterval: Int,
    val suggestedIntervalEffective: Int
)
