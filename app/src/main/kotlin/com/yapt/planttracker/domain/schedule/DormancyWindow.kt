package com.yapt.planttracker.domain.schedule

/**
 * Per-plant dormancy window membership (#699/#760, product ADR-0044): a discrete suspension of
 * watering reminders for an arbitrary, user-authored month range, distinct from
 * [SeasonalWatering]'s continuous multiplicative curve — see that ADR for why the seasonal cosine
 * cannot express this. Called from exactly one place, [CareSchedule.computeStatus] — every other
 * consumer of [com.yapt.planttracker.domain.model.PlantCareStatus] inherits suppression through
 * [com.yapt.planttracker.domain.model.PlantCareStatus.isDormant] rather than calling this directly.
 */
object DormancyWindow {

    private const val MIN_MONTH = 1
    private const val MAX_MONTH = 12

    /**
     * `true` when [month] (1-12) falls inside the window bounded by [startMonth]/[endMonth]
     * (inclusive on both ends):
     * - `startMonth <= endMonth` → `month in startMonth..endMonth` (e.g. 6-8, summer-dormant
     *   *Lithops*)
     * - `startMonth > endMonth` → `month >= startMonth || month <= endMonth` (e.g. 11-2, wraps the
     *   year boundary for northern-hemisphere winter dormancy)
     * - `startMonth == endMonth` → dormant in that single month only, via the first branch
     *
     * No [Hemisphere] coupling: unlike the computed seasonal curve, the user authors both months
     * directly, so a southern-hemisphere user simply enters their own months rather than the app
     * inferring a peak offset.
     *
     * Two deliberate, explicit edge cases rather than letting either fall through implicitly:
     * - **Either month `null` → never dormant.** Both `null` is the default (no window configured);
     *   exactly one `null` is a half-configured value that should never occur once the eventual
     *   editing UI (slice 4, #762) ships paired month pickers, but is still handled defensively here
     *   rather than assumed impossible.
     * - **Either month outside 1-12 → never dormant.** Fails closed rather than fails open: since
     *   the only effect of "dormant" is suppressing watering reminders, treating malformed data as
     *   "not dormant" risks a stale reminder at worst, while the reverse (treating it as dormant)
     *   risks silently starving a plant of reminders on bad data. There is no validating write path
     *   yet (no UI to enter a window at all in this slice), so this is a defensive floor, not
     *   evidence such a value is ever expected to occur in practice.
     */
    fun isDormant(month: Int, startMonth: Int?, endMonth: Int?): Boolean {
        if (startMonth == null || endMonth == null) return false
        val validRange = startMonth in MIN_MONTH..MAX_MONTH && endMonth in MIN_MONTH..MAX_MONTH
        return validRange && if (startMonth <= endMonth) {
            month in startMonth..endMonth
        } else {
            month >= startMonth || month <= endMonth
        }
    }
}
