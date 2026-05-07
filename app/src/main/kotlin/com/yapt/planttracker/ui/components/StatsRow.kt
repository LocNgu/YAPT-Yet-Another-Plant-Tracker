package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            label = "Watering",
            value = status.nextWateringDueAt?.let { DateUtils.formatCountdown(it) }
                ?: status.lastWateredAt?.let { DateUtils.formatRelative(it) }
                ?: "Never",
            valueColor = if (status.nextWateringDueAt != null) waterColor else null,
            subValue = if (status.nextWateringDueAt != null) {
                status.lastWateredAt?.let { DateUtils.formatRelative(it) }
            } else null,
            modifier = Modifier.weight(1f)
        )

        if (status.plant.fertilizingIntervalDays != null) {
            val fertColor = when {
                status.isFertilizingOverdue -> OverdueRed
                status.isFertilizingDueSoon -> WarnOrange
                else -> OkGreen
            }
            StatChip(
                label = "Fertilizing",
                value = status.nextFertilizingDueAt?.let { DateUtils.formatCountdown(it) }
                    ?: status.lastFertilizedAt?.let { DateUtils.formatRelative(it) }
                    ?: "Never",
                valueColor = if (status.nextFertilizingDueAt != null) fertColor else null,
                subValue = if (status.nextFertilizingDueAt != null) {
                    status.lastFertilizedAt?.let { DateUtils.formatRelative(it) }
                } else null,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatChip(
    label: String,
    value: String,
    valueColor: Color? = null,
    subValue: String? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = valueColor ?: MaterialTheme.colorScheme.primary
            )
            subValue?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
