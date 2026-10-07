package com.yapt.planttracker.ui.screens.plantdetail

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.yapt.planttracker.R

/**
 * Per-action tabs on Plant Detail (#436). The tab strip lives inside the Box overlay's scrolling
 * content, below the hero — see technical ADR-0018 (supersedes technical ADR-0005). Note has no tab and
 * can no longer be logged (#532, product ADR-0062); existing Note entries appear only in the combined
 * care-history list at the bottom of [HOME] (#530, product ADR-0060), as do historical Mist entries
 * (#875, product ADR-0061). Prune has its own tab and shows there too, and the Water tab lists just its
 * own WATER entries. New logs come only from the in-pane actions: the `+` FAB is gone and Add Care Log
 * only edits an existing log (#532, product ADR-0062).
 * [HOME] (#530, product ADR-0060) is the first tab and the landing tab ([DEFAULT]); Repot and Prune sit
 * behind the collapsed row's chevron (#532, product ADR-0062).
 * [CUSTOM_REMINDERS]/[ISSUES] (#590, product ADR-0043) fold what used to be the always-visible
 * `CustomRemindersCard`/`PlantIssuesCard` sections into the tab strip's collapsed-by-default second
 * row — see `PlantDetailScreen.kt`'s `PlantDetailTabStrip`. [ISSUES] reuses `PlantIssuesCard`'s own
 * `Icons.Filled.BugReport` for consistency between the tab icon and the card's own report-issue icon.
 */
enum class PlantDetailTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.plant_detail_tab_home, Icons.Filled.Home),
    WATER(R.string.plant_detail_tab_water, Icons.Filled.WaterDrop),
    FERTILIZE(R.string.plant_detail_tab_fertilize, Icons.Filled.Spa),
    PHOTO(R.string.plant_detail_tab_photo, Icons.Filled.PhotoLibrary),
    REPOT(R.string.plant_detail_tab_repot, Icons.Filled.LocalFlorist),
    PRUNE(R.string.plant_detail_tab_prune, Icons.Filled.ContentCut),
    CUSTOM_REMINDERS(R.string.plant_detail_tab_custom_reminders, Icons.Filled.Notifications),
    ISSUES(R.string.plant_detail_tab_issues, Icons.Filled.BugReport);

    val isInCollapsedRow: Boolean get() = ordinal >= COLLAPSED_TAB_COUNT

    companion object {
        /** How many tabs stay visible in `PlantDetailTabStrip`'s collapsed (default) state. */
        const val COLLAPSED_TAB_COUNT = 4

        /** The landing tab: the screen's initial selection, the unknown-route-arg fallback and the collapse reset. */
        val DEFAULT = HOME

        fun fromRouteArg(name: String?): PlantDetailTab? =
            name?.let { runCatching { valueOf(it) }.getOrDefault(DEFAULT) }
    }
}
