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

    val all: List<FeatureFlag> = listOf(PLANT_DETAIL_TABS)
}
