package com.yapt.planttracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.ui.theme.OkGreen
import com.yapt.planttracker.ui.theme.OverdueRed
import com.yapt.planttracker.ui.theme.WarnOrange
import com.yapt.planttracker.util.DateUtils

@Composable
fun PlantCard(
    status: PlantCareStatus,
    onClick: () -> Unit,
    onQuickWater: () -> Unit,
    onQuickFertilize: () -> Unit,
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
            modifier = Modifier.height(IntrinsicSize.Max),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(90.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
            ) {
                if (status.plant.coverPhotoUri != null) {
                    AsyncImage(
                        model = status.plant.coverPhotoUri,
                        contentDescription = "Plant photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocalFlorist,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp, bottom = 12.dp, end = 8.dp)) {
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
                            status.plant.useLiquidFertilizer -> "With watering"
                            status.nextFertilizingDueAt != null ->
                                DateUtils.formatCountdown(status.nextFertilizingDueAt)
                            status.lastFertilizedAt != null ->
                                "Fertilizing ${DateUtils.formatRelative(status.lastFertilizedAt)}"
                            else -> "Never fertilized"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Spa, null, Modifier.size(12.dp), tint = fertColor)
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

            Column(
                modifier = Modifier.padding(end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = onQuickWater,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.WaterDrop,
                        contentDescription = "Quick water",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onQuickFertilize,
                    modifier = Modifier.size(if (status.plant.useLiquidFertilizer) 44.dp else 36.dp)
                ) {
                    if (status.plant.useLiquidFertilizer) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.WaterDrop, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "+",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                            Icon(Icons.Filled.Spa, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Icon(Icons.Filled.Spa, "Quick fertilize", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
