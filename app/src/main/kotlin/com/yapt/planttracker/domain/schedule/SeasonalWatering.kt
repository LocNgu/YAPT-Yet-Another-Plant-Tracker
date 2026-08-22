package com.yapt.planttracker.domain.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/** Which half of the globe the device's timezone implies, for [SeasonalWatering]'s peak-day offset. */
enum class Hemisphere { NORTHERN, SOUTHERN }

/**
 * Global amplitude presets for the computed seasonal watering curve (#569, product ADR-0026):
 * `season(date) = 1 + amplitude * cos(...)`. Stored as its [name] String in DataStore, mirroring
 * [com.yapt.planttracker.ui.theme.ThemeMode] — read with
 * `runCatching { valueOf(...) }.getOrDefault(STANDARD)`.
 */
@Suppress("MagicNumber")
enum class SeasonalAmplitude(val value: Double) {
    OFF(0.0),
    MILD(0.2),
    STANDARD(0.35),
    STRONG(0.5)
}

/**
 * Computed (not learned) seasonal watering-interval factor (#569, product ADR-0026): a smooth
 * cosine curve peaking in early January (northern hemisphere) so a plant's *effective* watering
 * interval stretches in winter and compresses in summer, without any per-plant training data. See
 * `.claude/rules/seasonal-watering.md`.
 */
object SeasonalWatering {

    private const val DAYS_IN_YEAR = 365.0
    private const val NORTHERN_PEAK_DAY = 5
    private const val SOUTHERN_PEAK_OFFSET_DAYS = 182

    const val MIN_EFFECTIVE_INTERVAL_DAYS = 1
    const val MAX_EFFECTIVE_INTERVAL_DAYS = 180

    /**
     * Zone-ID prefixes treated as southern hemisphere. Equatorial/unmatched zones default to
     * northern (#569) — low-stakes there since seasonality is weak near the equator anyway.
     */
    private val SOUTHERN_HEMISPHERE_ZONE_PREFIXES = listOf(
        "Australia/",
        "Pacific/Auckland",
        "Pacific/Fiji",
        "America/Sao_Paulo",
        "America/Argentina/",
        "America/Santiago",
        "Africa/Johannesburg",
        "Indian/"
    )

    fun hemisphereForTimeZoneId(zoneId: String): Hemisphere =
        if (SOUTHERN_HEMISPHERE_ZONE_PREFIXES.any { zoneId.startsWith(it) }) {
            Hemisphere.SOUTHERN
        } else {
            Hemisphere.NORTHERN
        }

    /** Derived from the device's default timezone — no location permission, no network (#569). */
    fun currentHemisphere(): Hemisphere = hemisphereForTimeZoneId(TimeZone.getDefault().id)

    /**
     * `1 + amplitude * cos(2π * (dayOfYear - peakDay) / 365)`. [amplitude] = 0 collapses the curve
     * to a flat 1.0 (equivalent to the feature being off). Southern hemisphere shifts the peak by
     * [SOUTHERN_PEAK_OFFSET_DAYS] days (~half a year) so summer/winter fall on the correct months.
     */
    fun season(date: LocalDate, amplitude: Double, hemisphere: Hemisphere): Double {
        val peakDay = peakDayOfYear(hemisphere)
        return 1 + amplitude * cos(2 * PI * (date.dayOfYear - peakDay) / DAYS_IN_YEAR)
    }

    /** `round(base * season(date))`, clamped to [[MIN_EFFECTIVE_INTERVAL_DAYS], [MAX_EFFECTIVE_INTERVAL_DAYS]]. */
    fun effectiveInterval(base: Double, date: LocalDate, amplitude: Double, hemisphere: Hemisphere): Int =
        (base * season(date, amplitude, hemisphere)).roundToInt()
            .coerceIn(MIN_EFFECTIVE_INTERVAL_DAYS, MAX_EFFECTIVE_INTERVAL_DAYS)

    /**
     * The inverse of [effectiveInterval]'s multiplication: strips [date]'s seasonal factor back out
     * of [value] so it can be stored as a season-neutral reference (migration base, manual-edit
     * base, or Part 1's de-seasonalized observed gap — #569).
     */
    fun deseasonalize(value: Double, date: LocalDate, amplitude: Double, hemisphere: Hemisphere): Double =
        value / season(date, amplitude, hemisphere)

    /** [deseasonalize] rounded to a whole-day count, floored at 1 (mirrors [effectiveInterval]'s floor). */
    fun deseasonalizeToDays(
        value: Int,
        date: LocalDate,
        amplitude: Double,
        hemisphere: Hemisphere
    ): Int = deseasonalize(value.toDouble(), date, amplitude, hemisphere)
        .roundToInt()
        .coerceAtLeast(MIN_EFFECTIVE_INTERVAL_DAYS)

    /**
     * Day-of-year of [season]'s peak for [hemisphere], independent of amplitude (the peak's
     * calendar position never moves, only its height does). [season] itself calls this rather than
     * keeping a separate copy of the conditional. Also used by the seasonal-curve preview chart's
     * hemisphere caption (#579) to name the peak month.
     */
    fun peakDayOfYear(hemisphere: Hemisphere): Int =
        if (hemisphere == Hemisphere.SOUTHERN) {
            NORTHERN_PEAK_DAY + SOUTHERN_PEAK_OFFSET_DAYS
        } else {
            NORTHERN_PEAK_DAY
        }
}

/**
 * The effective seasonal amplitude for [CareSchedule]'s due-date computation and
 * [CareSchedule.computeAdaptiveInterval]'s de-seasonalization step: 0.0 (same as
 * [SeasonalAmplitude.OFF]) whenever [FeatureFlagRegistry.SEASONAL_WATERING] itself is off, so every
 * call site reads one flow rather than re-deriving "flag off => no seasonal effect" each time (#569).
 */
fun DataStore<Preferences>.seasonalAmplitudeFlow(): Flow<Double> = data.map { prefs ->
    val flagOn = prefs[FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING)]
        ?: FeatureFlagRegistry.SEASONAL_WATERING.default
    if (!flagOn) {
        SeasonalAmplitude.OFF.value
    } else {
        runCatching { SeasonalAmplitude.valueOf(prefs[SettingsKeys.SEASONAL_AMPLITUDE] ?: "") }
            .getOrDefault(SeasonalAmplitude.STANDARD)
            .value
    }
}

/** One-shot read of [seasonalAmplitudeFlow], for call sites outside a `combine {}`/StateFlow. */
suspend fun DataStore<Preferences>.seasonalAmplitudeOnce(): Double = seasonalAmplitudeFlow().first()
