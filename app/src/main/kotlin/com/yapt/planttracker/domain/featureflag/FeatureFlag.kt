package com.yapt.planttracker.domain.featureflag

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
 * The single source of truth for every feature flag in the app. Ships **empty** — the first
 * real flag arrives with the first experimental feature. When a feature graduates, its
 * [FeatureFlag] entry and both code paths (flag on / flag off) are deleted from the graduating
 * PR so flags never accumulate (see product ADR-0022, "Extended by #521").
 */
object FeatureFlagRegistry {
    val all: List<FeatureFlag> = emptyList()
}
