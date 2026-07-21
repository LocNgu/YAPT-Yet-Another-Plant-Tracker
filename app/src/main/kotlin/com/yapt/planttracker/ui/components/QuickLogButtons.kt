package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.PlantCareStatus

/**
 * The shared quick-log button pair used by both the plant-overview list ([PlantCard]) and the
 * calendar day sheet. A water button and a fertilize button, stacked vertically.
 *
 * For liquid-fertilizer plants (`status.plant.useLiquidFertilizer`) the fertilize button becomes a
 * combined water+fertilize button (💧 + 🌿), since fertilizing rides along with watering
 * (ADR-0008/ADR-0017); the plain water button remains so the user can water without fertilizing.
 *
 * Keeping this in one place means the two surfaces never drift apart in appearance or behaviour.
 */
@Composable
fun QuickLogButtons(
    status: PlantCareStatus,
    onQuickWater: () -> Unit,
    onQuickFertilize: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = onQuickWater,
            enabled = enabled,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Filled.WaterDrop,
                contentDescription = stringResource(R.string.quick_log_water_cd),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = onQuickFertilize,
            enabled = enabled,
            modifier = Modifier.size(if (status.plant.useLiquidFertilizer) 44.dp else 36.dp)
        ) {
            if (status.plant.useLiquidFertilizer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WaterDrop, stringResource(R.string.quick_log_fertilize_cd), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                    Icon(Icons.Filled.Spa, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Icon(Icons.Filled.Spa, stringResource(R.string.quick_log_fertilize_cd), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
