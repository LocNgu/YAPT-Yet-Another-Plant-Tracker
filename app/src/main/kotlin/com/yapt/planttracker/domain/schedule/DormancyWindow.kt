package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.util.toLocalDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Per-plant dormancy window membership (#699/#760, product ADR-0044): a discrete suspension of
 * watering reminders for an arbitrary, user-authored month range, distinct from
 * [SeasonalWatering]'s continuous multiplicative curve — see that ADR for why the seasonal cosine
 * cannot express this. [CareSchedule.computeStatus] uses it for the current status; Calendar also
 * checks future watering dates so its grid never presents a date inside the configured window as
 * an active watering due date.
 */
object DormancyWindow {

    const val MIN_WATERING_INTERVAL_WEEKS = 1
    const val MAX_WATERING_INTERVAL_WEEKS = 12
    const val DEFAULT_WATERING_INTERVAL_WEEKS = 5
    private const val DAYS_PER_WEEK = 7

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

    /** First day of the dormant cycle containing [date], or `null` for an inactive/malformed window. */
    fun currentCycleStart(date: LocalDate, startMonth: Int?, endMonth: Int?): LocalDate? {
        return if (isDormant(date.monthValue, startMonth, endMonth)) {
            val start = checkNotNull(startMonth)
            val end = checkNotNull(endMonth)
            val startYear = if (start > end && date.monthValue <= end) date.year - 1 else date.year
            LocalDate.of(startYear, start, 1)
        } else {
            null
        }
    }

    /** A dormant cadence is whole weeks, from one to twelve weeks inclusive (#785, product ADR-0047). */
    fun validWateringInterval(days: Int?): Int? = days?.takeIf {
        it % DAYS_PER_WEEK == 0 && it / DAYS_PER_WEEK in MIN_WATERING_INTERVAL_WEEKS..MAX_WATERING_INTERVAL_WEEKS
    }

    fun wateringIntervalWeeks(days: Int?): Int? = validWateringInterval(days)?.div(DAYS_PER_WEEK)

    fun wateringIntervalDays(weeks: Int): Int {
        require(weeks in MIN_WATERING_INTERVAL_WEEKS..MAX_WATERING_INTERVAL_WEEKS)
        return weeks * DAYS_PER_WEEK
    }

    /**
     * Walking this many consecutive calendar months (starting anywhere) necessarily visits every
     * month-of-year value at least once — the basis for [spansDormancy]'s long-span shortcut below.
     */
    private const val MONTHS_IN_YEAR = 12

    /**
     * `true` when the calendar span from [fromMillis] to [toMillis] (both inclusive, at month
     * granularity) touches at least one month inside the dormancy window bounded by
     * [startMonth]/[endMonth] — i.e. "did dormancy occur anywhere between a previous watering and a
     * new one?" (#699/#761, product ADR-0044). This is the predicate that protects the adaptive model:
     * a watering gap that crosses (or falls entirely inside) the window is excluded from base learning
     * and skips the reason prompt, since the gap length reflects the plant sleeping, not going thirsty.
     *
     * Both endpoints' own months count as part of the span — a watering logged *during* the window
     * (both endpoints dormant, or one of them) is "spanning" too, matching the product decision that
     * watering during dormancy is accepted but still excluded from learning, not just a gap that
     * crosses the window's boundaries from outside.
     *
     * Three cases the month-walk below must get right without special-casing (#761):
     * - **A span wholly inside the window** (e.g. previous watering Dec 5, new one Jan 10, inside a
     *   Nov-Feb window) — the very first month walked (Dec) is already dormant, so this returns `true`
     *   immediately; no different from any other overlap.
     * - **A span crossing the window exactly once** (e.g. Oct 25 → Mar 1, straddling Nov-Feb) — the
     *   walk reaches November and returns `true`; the canonical example from the issue.
     * - **A span crossing the window twice, or longer than a year** (e.g. a gap spanning two winters)
     *   — handled by the shortcut below rather than walking a potentially enormous number of months:
     *   once the span covers [MONTHS_IN_YEAR] or more distinct calendar months, every month-of-year
     *   value (1-12) necessarily occurs at least once in it (any [MONTHS_IN_YEAR] consecutive integers
     *   taken modulo 12 cover every residue exactly once), so a validly-configured window (guaranteed
     *   at least one dormant month by [isDormant]'s own contract) is guaranteed to overlap. The
     *   shortcut is only applied once [startMonth]/[endMonth] are already known valid — an
     *   invalid/out-of-range window has *zero* dormant months, so the shortcut's premise would not
     *   hold for it, and control never reaches the shortcut in that case since the null/range guard
     *   below already returns `false` first.
     *
     * `false` when either month is `null`/out-of-range (mirrors [isDormant]'s own fail-closed posture)
     * or when [toMillis] is not strictly after [fromMillis] (a degenerate or reversed span has nothing
     * to walk).
     */
    @Suppress("ReturnCount")
    fun spansDormancy(startMonth: Int?, endMonth: Int?, fromMillis: Long, toMillis: Long): Boolean {
        if (startMonth == null || endMonth == null) return false
        if (startMonth !in MIN_MONTH..MAX_MONTH || endMonth !in MIN_MONTH..MAX_MONTH) return false
        if (toMillis <= fromMillis) return false

        val fromDate = fromMillis.toLocalDate()
        val toDate = toMillis.toLocalDate()
        val monthsBetween = ChronoUnit.MONTHS.between(fromDate.withDayOfMonth(1), toDate.withDayOfMonth(1))
        if (monthsBetween >= MONTHS_IN_YEAR - 1) return true

        var month = fromDate.monthValue
        repeat((monthsBetween + 1).toInt()) {
            if (isDormant(month, startMonth, endMonth)) return true
            month = if (month == MAX_MONTH) MIN_MONTH else month + 1
        }
        return false
    }
}
