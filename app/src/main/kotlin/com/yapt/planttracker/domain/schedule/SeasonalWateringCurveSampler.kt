package com.yapt.planttracker.domain.schedule

import java.time.LocalDate

/**
 * One sampled calendar day of [SeasonalWatering.season]'s yearly curve, for the seasonal-curve
 * preview chart (#579, follow-up to #569/product ADR-0026).
 */
data class SeasonalCurvePoint(
    val date: LocalDate,
    val dayOfYear: Int,
    val multiplier: Double
)

/**
 * Samples [SeasonalWatering.season] once per calendar day across a full year, for the Settings and
 * Plant Detail preview chart. Pure/UI-agnostic and visualization-only — never consumed by
 * [CareSchedule] or any due-date computation.
 */
object SeasonalWateringCurveSampler {

    private const val DAYS_IN_COMMON_YEAR = 365
    private const val DAYS_IN_LEAP_YEAR = 366

    /**
     * [referenceYear] only decides which dates are enumerated (e.g. whether a Feb 29 sample point
     * appears) — [SeasonalWatering.season]'s underlying formula is periodic on the same 365-day
     * basis regardless of leap years, so callers can pass any year and get the same curve shape.
     * Defaults to the current year so a caller with no reason to pick a specific one still gets a
     * leap-year-correct sample count.
     */
    fun sample(
        amplitude: Double,
        hemisphere: Hemisphere,
        referenceYear: Int = LocalDate.now().year
    ): List<SeasonalCurvePoint> {
        val jan1 = LocalDate.of(referenceYear, 1, 1)
        val daysInYear = if (jan1.isLeapYear) DAYS_IN_LEAP_YEAR else DAYS_IN_COMMON_YEAR
        val points = mutableListOf<SeasonalCurvePoint>()
        for (offset in 0 until daysInYear) {
            val date = jan1.plusDays(offset.toLong())
            points.add(
                SeasonalCurvePoint(
                    date = date,
                    dayOfYear = date.dayOfYear,
                    multiplier = SeasonalWatering.season(date, amplitude, hemisphere)
                )
            )
        }
        return points
    }
}
