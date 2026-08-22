package com.yapt.planttracker.domain.featureflag

import com.yapt.planttracker.R

/**
 * A single runtime-toggleable experimental feature, rendered generically by the Developer
 * section's flag list (title, description, and a Switch — no per-flag UI code required).
 *
 * @param key stable identifier used to derive the flag's DataStore key; never reused across
 *   flags, even after one is removed.
 * @param titleRes displayed as the flag row's title.
 * @param descriptionRes displayed as the flag row's subtitle.
 * @param default the value the flag resets to when developer mode is turned off. Must be
 *   `false` for every shipped flag (product ADR-0022) — every feature starts off in both debug
 *   and release builds.
 */
data class FeatureFlag(
    val key: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val default: Boolean
)

/**
 * The single source of truth for every feature flag in the app. When a feature graduates, its
 * [FeatureFlag] entry and both code paths (flag on / flag off) are deleted from the graduating
 * PR so flags never accumulate (see product ADR-0022, "Extended by #521").
 */
object FeatureFlagRegistry {

    /**
     * Plant Detail per-action tabs, inline scheduling settings, and per-tab insights (#436).
     * Off renders the classic single-page Plant Detail (chart, photo gallery, care history).
     */
    val PLANT_DETAIL_TABS = FeatureFlag(
        key = "plant_detail_tabs",
        titleRes = R.string.feature_flag_plant_detail_tabs_title,
        descriptionRes = R.string.feature_flag_plant_detail_tabs_description,
        default = false
    )

    /**
     * Multiplicative + confidence-weighted watering interval adaptation (#568, technical ADR-0021).
     * Off: `CareSchedule.computeSuggestedInterval()` behaves exactly as today (±1 day nudge). On:
     * the ADR-0006 suggestion dialog is driven by `CareSchedule.computeAdaptiveInterval()` instead,
     * and `Plant.wateringConfidence` is tracked. The column and `.yapt` backup field ship
     * unconditionally regardless of this flag's state.
     */
    val ADAPTIVE_WATERING = FeatureFlag(
        key = "adaptive_watering",
        titleRes = R.string.feature_flag_adaptive_watering_title,
        descriptionRes = R.string.feature_flag_adaptive_watering_description,
        default = false
    )

    /**
     * Computed (not learned) seasonal watering factor (#569, product ADR-0026). Off: due dates read
     * `Plant.wateringIntervalDays` exactly as today, and the season factor is never applied. On: the
     * amplitude setting appears on the main Settings screen and `CareSchedule` multiplies each
     * unpinned plant's `Plant.wateringBaseIntervalDays` by the seasonal curve for due-date math (and
     * de-seasonalizes the observed gap before `ADAPTIVE_WATERING` learns from it). The
     * `wateringBaseIntervalDays`/`pinIntervalToBase` columns and `.yapt` backup fields ship
     * unconditionally regardless of this flag's state, mirroring `ADAPTIVE_WATERING`'s precedent.
     */
    val SEASONAL_WATERING = FeatureFlag(
        key = "seasonal_watering",
        titleRes = R.string.feature_flag_seasonal_watering_title,
        descriptionRes = R.string.feature_flag_seasonal_watering_description,
        default = false
    )

    /**
     * Reframes the watering reminder notification from an instruction ("Water {plant}") to a
     * prompt ("Check {plant}") with **Watered** and **Still moist** actions (#570, product ADR-0027).
     * Off: the notification is byte-for-byte identical to today (title = plant name, "Skip watering"
     * action). On, for a watering-due plant only: title becomes "Check {plant}", and the single
     * "Skip watering" action is replaced by "Watered" (same deep-link as tapping the notification) and
     * "Still moist" (`StillMoistReceiver` — writes a `CareType.CHECK` log and defers the due date, no
     * screen shown; also feeds `ADAPTIVE_WATERING`'s update rule when that flag is also on).
     */
    val CHECK_REMINDERS = FeatureFlag(
        key = "check_reminders",
        titleRes = R.string.feature_flag_check_reminders_title,
        descriptionRes = R.string.feature_flag_check_reminders_description,
        default = false
    )

    val all: List<FeatureFlag> = listOf(PLANT_DETAIL_TABS, ADAPTIVE_WATERING, SEASONAL_WATERING, CHECK_REMINDERS)
}
