package com.yapt.planttracker.ui.screens.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.today.TodayWateredPlant
import com.yapt.planttracker.ui.components.PlantPhoto

internal const val WATERED_TODAY_VISIBLE_COUNT = 5

private const val WATERED_TODAY_SLOT_COUNT = WATERED_TODAY_VISIBLE_COUNT + 1
private const val WATERED_NAME_MAX_LINES = 2
private val WATERED_SLOT_MAX_WIDTH = 72.dp
private val WATERED_MIN_TARGET = 48.dp
private val WATERED_SLOT_INSET = 4.dp
private val WATERED_SLOT_SHAPE = RoundedCornerShape(12.dp)
private val WATERED_BADGE_SIZE = 20.dp
private val WATERED_BADGE_ICON_SIZE = 14.dp
private val WATERED_BADGE_RING = 2.dp
private val NOTHING_DUE_ICON_SIZE = 18.dp

internal fun wateredTodaySlotWidth(available: Dp): Dp =
    minOf(WATERED_SLOT_MAX_WIDTH, available / WATERED_TODAY_SLOT_COUNT).coerceAtLeast(WATERED_MIN_TARGET)

internal fun wateredTodayPhotoCount(plantCount: Int, expanded: Boolean): Int =
    if (plantCount > WATERED_TODAY_VISIBLE_COUNT && !expanded) WATERED_TODAY_VISIBLE_COUNT else plantCount

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WateredTodayRow(
    plants: List<TodayWateredPlant>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpen: (Long) -> Unit
) {
    val overflows = plants.size > WATERED_TODAY_VISIBLE_COUNT
    val shown = plants.take(wateredTodayPhotoCount(plants.size, expanded))
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val slotWidth = wateredTodaySlotWidth(maxWidth)
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            for (watered in shown) {
                key(watered.plant.id) {
                    WateredPlantSlot(watered, slotWidth) { onOpen(watered.plant.id) }
                }
            }
            if (overflows) {
                if (expanded) {
                    WateredShowLessSlot(slotWidth, onToggleExpanded)
                } else {
                    WateredMoreSlot(plants.size - WATERED_TODAY_VISIBLE_COUNT, slotWidth, onToggleExpanded)
                }
            }
        }
    }
}

@Composable
private fun WateredPlantSlot(watered: TodayWateredPlant, slotWidth: Dp, onClick: () -> Unit) {
    val description = stringResource(R.string.care_watered_today_photo_cd, watered.plant.name)
    Column(
        modifier = Modifier
            .width(slotWidth)
            .clip(WATERED_SLOT_SHAPE)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(WATERED_SLOT_INSET),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Decorative: the slot's description already says the plant was watered today.
        Box(modifier = Modifier.clearAndSetSemantics { }) {
            PlantPhoto(uri = watered.plant.coverPhotoUri, size = slotWidth - WATERED_SLOT_INSET * 2)
            WateredCheckBadge(Modifier.align(Alignment.BottomEnd))
        }
        WateredNameLabel(watered.plant.name)
    }
}

@Composable
private fun WateredCheckBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(WATERED_BADGE_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .border(WATERED_BADGE_RING, MaterialTheme.colorScheme.background, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            modifier = Modifier.size(WATERED_BADGE_ICON_SIZE),
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun WateredNameLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        maxLines = WATERED_NAME_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun WateredMoreSlot(hiddenCount: Int, slotWidth: Dp, onClick: () -> Unit) {
    val description = pluralStringResource(R.plurals.care_watered_today_more_cd, hiddenCount, hiddenCount)
    Box(
        modifier = Modifier
            .width(slotWidth)
            .heightIn(min = WATERED_MIN_TARGET)
            .clip(WATERED_SLOT_SHAPE)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(WATERED_SLOT_INSET),
        contentAlignment = Alignment.TopCenter
    ) {
        WateredCircle(slotWidth) {
            Text(
                text = stringResource(R.string.care_watered_today_more, hiddenCount),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun WateredShowLessSlot(slotWidth: Dp, onClick: () -> Unit) {
    val description = stringResource(R.string.care_watered_today_less_cd)
    Column(
        modifier = Modifier
            .width(slotWidth)
            .clip(WATERED_SLOT_SHAPE)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(WATERED_SLOT_INSET),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WateredCircle(slotWidth) {
            Icon(
                imageVector = Icons.Filled.ExpandLess,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        WateredNameLabel(stringResource(R.string.care_watered_today_less))
    }
}

@Composable
private fun WateredCircle(slotWidth: Dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(slotWidth - WATERED_SLOT_INSET * 2)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
internal fun NothingDueTodayLine() {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = WATERED_MIN_TARGET).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            modifier = Modifier.size(NOTHING_DUE_ICON_SIZE),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.care_nothing_due_today),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
