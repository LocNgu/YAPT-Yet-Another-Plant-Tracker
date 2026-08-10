package com.yapt.planttracker.ui.screens.plantdetail

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.yapt.planttracker.R

/**
 * Per-action tabs on Plant Detail (#436). The tab strip lives inside the Box overlay's scrolling
 * content, below the hero — see technical ADR-0018 (supersedes ADR-0005). Misting is folded into the
 * Water tab; Prune and Note have no tab and remain in the unified care-history list below the tabs.
 */
enum class PlantDetailTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    WATER(R.string.plant_detail_tab_water, Icons.Filled.WaterDrop),
    FERTILIZE(R.string.plant_detail_tab_fertilize, Icons.Filled.Spa),
    REPOT(R.string.plant_detail_tab_repot, Icons.Filled.LocalFlorist),
    PHOTO(R.string.plant_detail_tab_photo, Icons.Filled.PhotoLibrary)
}
