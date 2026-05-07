package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.ui.theme.OkGreen
import com.yapt.planttracker.ui.theme.OverdueRed
import com.yapt.planttracker.ui.theme.WarnOrange
import com.yapt.planttracker.util.DateUtils

@Composable
fun PlantCard(
    status: PlantCareStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlantPhoto(
                uri = status.plant.coverPhotoUri,
                size = 64.dp,
                rounded = true
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status.plant.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                status.plant.species?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val waterColor = when {
                        status.isOverdue -> OverdueRed
                        status.isDueSoon -> WarnOrange
                        else -> OkGreen
                    }
                    val waterLabel = when {
                        status.nextWateringDueAt != null ->
                            DateUtils.formatCountdown(status.nextWateringDueAt)
                        status.lastWateredAt != null ->
                            DateUtils.formatRelative(status.lastWateredAt)
                        else -> "Never watered"
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.WaterDrop,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = waterColor
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = waterLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = waterColor
                        )
                    }

                    if (status.plant.fertilizingIntervalDays != null) {
                        val fertColor = when {
                            status.isFertilizingOverdue -> OverdueRed
                            status.isFertilizingDueSoon -> WarnOrange
                            else -> OkGreen
                        }
                        val fertLabel = when {
                            status.nextFertilizingDueAt != null ->
                                DateUtils.formatCountdown(status.nextFertilizingDueAt)
                            status.lastFertilizedAt != null ->
                                "Fertilizing ${DateUtils.formatRelative(status.lastFertilizedAt)}"
                            else -> "Never fertilized"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Spa,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = fertColor
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = fertLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = fertColor
                            )
                        }
                    }
                }
            }
        }
    }
}
