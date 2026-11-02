package com.yapt.planttracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.WateringScheduleMode
import com.yapt.planttracker.ui.theme.IssuePurple
import com.yapt.planttracker.ui.theme.OkGreen
import com.yapt.planttracker.ui.theme.OverdueRed
import com.yapt.planttracker.ui.theme.WarnOrange
import com.yapt.planttracker.ui.util.relativeDateText
import com.yapt.planttracker.util.DateUtils

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
    PlantCardLayout(
        name = status.plant.name,
        species = status.plant.species,
        photoUri = status.plant.coverPhotoUri,
        onClick = onClick,
        modifier = modifier,
        selectionMode = selectionMode,
        selected = selected,
        onLongClick = onLongClick,
        onToggleSelect = onToggleSelect,
        badge = {
            if (status.activeIssueCount > 0) {
                IssueBadge(count = status.activeIssueCount)
            }
        },
        statusLine = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WaterStatusLabel(status)
                FertilizerStatusLabel(status)
            }
        },
        trailing = {
            // Always compose the quick-log buttons so the card keeps the same height in
            // selection mode; while selecting they're hidden (alpha 0) and disabled, so taps
            // over that area fall through to the card's selection toggle instead of quick-logging.
            QuickLogButtons(
                status = status,
                onQuickWater = onQuickWater,
                onQuickFertilize = onQuickFertilize,
                enabled = !selectionMode,
                modifier = Modifier
                    .padding(end = 8.dp, top = 8.dp, bottom = 8.dp)
                    // While selecting, hide them and drop them from the a11y tree so TalkBack
                    // doesn't stop on two dimmed buttons per card — the card's own `selected`
                    // Button semantics are the only thing that should be announced.
                    .then(
                        if (selectionMode) {
                            Modifier
                                .alpha(0f)
                                .clearAndSetSemantics {}
                        } else {
                            Modifier
                        }
                    )
            )
        }
    )
}

@Composable
private fun BoxScope.IssueBadge(count: Int) {
    val badgeDescription = pluralStringResource(R.plurals.cd_plant_card_active_issues, count, count)
    Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .clip(RoundedCornerShape(50))
            .background(IssuePurple)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics { contentDescription = badgeDescription },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.BugReport,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = Color.White
        )
        if (count > 1) {
            Spacer(Modifier.width(2.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }
    }
}

@Composable
private fun WaterStatusLabel(status: PlantCareStatus) {
    val waterColor = when {
        status.isOverdue -> OverdueRed
        status.isDueSoon -> WarnOrange
        status.wateringScheduleMode == WateringScheduleMode.DORMANT_SUSPENDED ->
            MaterialTheme.colorScheme.onSurfaceVariant
        else -> OkGreen
    }
    val neverWateredLabel = stringResource(R.string.water_label_never_watered)
    val waterLabel = when {
        status.wateringScheduleMode == WateringScheduleMode.DORMANT_SUSPENDED ->
            stringResource(R.string.date_group_dormant)
        status.lastWateredAt == null -> neverWateredLabel
        status.nextWateringDueAt != null ->
            DateUtils.formatCountdown(status.nextWateringDueAt)
        else -> relativeDateText(status.lastWateredAt)
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
}

@Composable
private fun FertilizerStatusLabel(status: PlantCareStatus) {
    if (status.plant.fertilizingIntervalDays == null) return

    val fertColor = when {
        status.isDormant -> MaterialTheme.colorScheme.onSurfaceVariant
        status.isFertilizingOverdue -> OverdueRed
        status.isFertilizingDueSoon -> WarnOrange
        else -> OkGreen
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Spa, null, Modifier.size(12.dp), tint = fertColor)
        Spacer(Modifier.width(2.dp))
        Text(
            text = fertilizerLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = fertColor
        )
    }
}

@Composable
private fun fertilizerLabel(status: PlantCareStatus): String {
    val fertLabelNever = stringResource(R.string.fert_label_never_fertilized)
    val fertLabelFertilizing = status.lastFertilizedAt?.let {
        stringResource(R.string.fert_label_fertilizing, relativeDateText(it))
    }
    return when {
        status.isDormant -> stringResource(R.string.date_group_dormant)
        status.plant.useLiquidFertilizer &&
            (status.isFertilizingOverdue || status.isFertilizingDueSoon) ->
            stringResource(R.string.fert_label_due_with_watering)
        status.lastFertilizedAt == null -> fertLabelNever
        status.nextFertilizingDueAt != null ->
            DateUtils.formatCountdown(status.nextFertilizingDueAt)
        else -> fertLabelFertilizing ?: fertLabelNever
    }
}
