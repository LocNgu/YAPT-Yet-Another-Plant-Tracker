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
        StatChip(
            label = "Last watered",
            value = status.lastWateredAt?.let { DateUtils.formatRelative(it) } ?: "Never",
            modifier = Modifier.weight(1f)
        )
        StatChip(
            label = "Last fertilized",
            value = status.lastFertilizedAt?.let { DateUtils.formatRelative(it) } ?: "Never",
            modifier = Modifier.weight(1f)
        )
        StatChip(
            label = "Total logs",
            value = "${status.totalCareLogs}",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun CareCountdownChips(
    status: PlantCareStatus,
    modifier: Modifier = Modifier
) {
    if (status.nextWateringDueAt == null && status.nextFertilizingDueAt == null) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        status.nextWateringDueAt?.let { dueAt ->
            val color = when {
                status.isOverdue -> OverdueRed
                status.isDueSoon -> WarnOrange
                else -> OkGreen
            }
            StatChip(
                label = "Next watering",
                value = DateUtils.formatCountdown(dueAt),
                valueColor = color,
                modifier = Modifier.weight(1f)
            )
        }

        status.nextFertilizingDueAt?.let { dueAt ->
            val color = when {
                status.isFertilizingOverdue -> OverdueRed
                status.isFertilizingDueSoon -> WarnOrange
                else -> OkGreen
            }
            StatChip(
                label = "Next fertilizing",
                value = DateUtils.formatCountdown(dueAt),
                valueColor = color,
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
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
