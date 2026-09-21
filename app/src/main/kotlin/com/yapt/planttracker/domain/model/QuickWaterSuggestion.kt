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
 *
 * [currentIntervalEffective] (#716) is today's live-recomputed effective interval — what
 * [com.yapt.planttracker.domain.schedule.CareSchedule.effectiveWateringIntervalDaysForDisplay] would
 * show for the plant's *pre*-observation base, evaluated at the same date [suggestedIntervalEffective]
 * is. It is the number [QuickLogUseCase.computeSuggestion] actually gated this suggestion against —
 * never the stale `Plant.wateringIntervalDays` literal, which only updates on a manual edit /
 * suggestion apply / the #571 bootstrap / the #702 fixup and drifts from the true seasonal value on
 * its own. Every "currently N days" display (Plant Detail, Calendar, Plant List) reads this field
 * instead of independently re-deriving it from the stale literal — a 4th and 5th live copy of the
 * same bug #716 fixed at the source. Deliberately **not** defaulted, for the same reason
 * [suggestedBaseInterval] isn't: a construction site must consider it rather than silently falling
 * back to the (wrong) literal.
 */
data class QuickWaterSuggestion(
    val plantId: Long,
    val plantName: String,
    val suggestedInterval: Int,
    val suggestedIntervalEffective: Int,
    val suggestedBaseInterval: Double,
    val currentIntervalEffective: Int
)
