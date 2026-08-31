package com.yapt.planttracker.domain.model

/**
 * Carries a watering-interval suggestion from a quick-water action back to the calling screen.
 * [suggestedInterval] is the model's raw, season-neutral **base**-space value — the write path
 * (Apply / the dialog's editable field) stays bound to it unchanged. [suggestedIntervalEffective] is
 * the same suggestion converted to effective (display) space via
 * [com.yapt.planttracker.domain.schedule.CareSchedule.effectiveWateringIntervalDaysForDisplay] — the
 * ADR-0006 suggestion dialog's body text and its "should this even show" gate both use this value
 * instead, so they never compare a base-space number against an already seasonally-adjusted "current"
 * (#620). Equal to [suggestedInterval] whenever the plant is pinned or `SEASONAL_WATERING` is off.
 */
data class QuickWaterSuggestion(
    val plantId: Long,
    val plantName: String,
    val suggestedInterval: Int,
    val suggestedIntervalEffective: Int
)
