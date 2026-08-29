package com.yapt.planttracker.ui.screens.plantdetail

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.yapt.planttracker.R

/**
 * Per-action tabs on Plant Detail (#436). The tab strip lives inside the Box overlay's scrolling
 * content, below the hero — see technical ADR-0018 (supersedes ADR-0005). Misting is folded into the
 * Water tab; Prune and Note have no tab and remain in the unified care-history list below the tabs.
 * [CUSTOM_REMINDERS]/[ISSUES] (#590, product ADR-0030) fold what used to be the always-visible
 * `CustomRemindersCard`/`PlantIssuesCard` sections into the tab strip's collapsed-by-default second
 * row — see `PlantDetailScreen.kt`'s `PlantDetailTabStrip`. [ISSUES] reuses `PlantIssuesCard`'s own
 * `Icons.Filled.BugReport` for consistency between the tab icon and the card's own report-issue icon.
 */
enum class PlantDetailTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    WATER(R.string.plant_detail_tab_water, Icons.Filled.WaterDrop),
    FERTILIZE(R.string.plant_detail_tab_fertilize, Icons.Filled.Spa),
    REPOT(R.string.plant_detail_tab_repot, Icons.Filled.LocalFlorist),
    PHOTO(R.string.plant_detail_tab_photo, Icons.Filled.PhotoLibrary),
    CUSTOM_REMINDERS(R.string.plant_detail_tab_custom_reminders, Icons.Filled.Notifications),
    ISSUES(R.string.plant_detail_tab_issues, Icons.Filled.BugReport)
}
