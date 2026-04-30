package com.yapt.planttracker.domain.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector

enum class CareType(
    val displayName: String,
    val icon: ImageVector
) {
    WATER("Watered", Icons.Filled.WaterDrop),
    FERTILIZE("Fertilized", Icons.Filled.Spa),
    PRUNE("Pruned", Icons.Filled.ContentCut),
    MIST("Misted", Icons.Filled.Shower),
    REPOT("Repotted", Icons.Filled.LocalFlorist),
    NOTE("Note", Icons.Filled.Notes),
    PHOTO("Photo", Icons.Filled.AutoAwesome)
}
