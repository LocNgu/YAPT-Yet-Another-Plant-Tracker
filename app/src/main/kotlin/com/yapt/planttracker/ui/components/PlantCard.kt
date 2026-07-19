package com.yapt.planttracker.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.ui.theme.OkGreen
import com.yapt.planttracker.ui.theme.OverdueRed
import com.yapt.planttracker.ui.theme.WarnOrange
import com.yapt.planttracker.util.DateUtils

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlantCard(
    status: PlantCareStatus,
    onClick: () -> Unit,
    onQuickWater: () -> Unit,
    onQuickFertilize: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
    onToggleSelect: () -> Unit = {}
) {
    // Local copy so the semantics lambda below can reference it without colliding with the
    // write-only `selected` SemanticsPropertyReceiver property of the same name.
    val isSelected = selected
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onClick() },
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.cd_bulk_select_plant),
                role = Role.Button
            )
            // In selection mode, expose the checked state so TalkBack announces
            // "selected" / "not selected" as the user moves between cards.
            .then(
                if (selectionMode) {
                    Modifier.semantics { selected = isSelected }
                } else {
                    Modifier
                }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
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
                        contentDescription = stringResource(R.string.cd_plant_photo),
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

                if (selectionMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    ) {
                        Checkbox(checked = selected, onCheckedChange = null)
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
                    val neverWateredLabel = stringResource(R.string.water_label_never_watered)
                    val waterLabel = when {
                        status.lastWateredAt == null -> neverWateredLabel
                        status.nextWateringDueAt != null ->
                            DateUtils.formatCountdown(status.nextWateringDueAt)
                        else -> DateUtils.formatRelative(status.lastWateredAt)
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
                        val fertLabelNever = stringResource(R.string.fert_label_never_fertilized)
                        val fertLabelFertilizing = status.lastFertilizedAt?.let {
                            stringResource(R.string.fert_label_fertilizing, DateUtils.formatRelative(it))
                        }
                        val fertLabel = when {
                            status.plant.useLiquidFertilizer &&
                                    (status.isFertilizingOverdue || status.isFertilizingDueSoon) ->
                                stringResource(R.string.fert_label_due_with_watering)
                            status.lastFertilizedAt == null -> fertLabelNever
                            status.nextFertilizingDueAt != null ->
                                DateUtils.formatCountdown(status.nextFertilizingDueAt)
                            else -> fertLabelFertilizing ?: fertLabelNever
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

            if (!selectionMode) {
                QuickLogButtons(
                    status = status,
                    onQuickWater = onQuickWater,
                    onQuickFertilize = onQuickFertilize,
                    modifier = Modifier.padding(end = 8.dp, top = 8.dp, bottom = 8.dp)
                )
            }
        }
    }
}
