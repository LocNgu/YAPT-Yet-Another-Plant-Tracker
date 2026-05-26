package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.ui.theme.OkGreen
import com.yapt.planttracker.ui.theme.OverdueRed
import com.yapt.planttracker.ui.theme.WarnOrange
import com.yapt.planttracker.util.DateUtils

@Composable
fun StatsRow(
    status: PlantCareStatus,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val waterColor = when {
            status.isOverdue -> OverdueRed
            status.isDueSoon -> WarnOrange
            else -> OkGreen
        }
        StatChip(
            icon = Icons.Filled.WaterDrop,
            label = "Watering",
            nextLine = status.nextWateringDueAt?.let { DateUtils.formatCountdown(it).lowercase() },
            nextColor = if (status.nextWateringDueAt != null) waterColor else null,
            lastLine = status.lastWateredAt?.let { DateUtils.formatRelative(it).lowercase() },
            modifier = Modifier.weight(1f)
        )

        if (status.plant.fertilizingIntervalDays != null) {
            val fertColor = when {
                status.isFertilizingOverdue -> OverdueRed
                status.isFertilizingDueSoon -> WarnOrange
                else -> OkGreen
            }
            StatChip(
                icon = Icons.Filled.Spa,
                leadingIcon = if (status.plant.useLiquidFertilizer) Icons.Filled.WaterDrop else null,
                label = "Fertilizing",
                nextLine = status.nextFertilizingDueAt?.let { DateUtils.formatCountdown(it).lowercase() },
                nextColor = if (status.nextFertilizingDueAt != null) fertColor else null,
                lastLine = status.lastFertilizedAt?.let { DateUtils.formatRelative(it).lowercase() },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatChip(
    icon: ImageVector,
    leadingIcon: ImageVector? = null,
    label: String,
    nextLine: String?,
    nextColor: Color?,
    lastLine: String?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (nextLine == null && lastLine == null) {
                Text(
                    text = "never",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                nextLine?.let {
                    Text(
                        text = if (it.startsWith("overdue")) it else "next: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = nextColor ?: MaterialTheme.colorScheme.primary
                    )
                }
                lastLine?.let {
                    Text(
                        text = "last: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
