package com.yapt.planttracker.ui.screens.watering

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.watering.WateringOverview
import com.yapt.planttracker.domain.watering.WateringOverviewGroup
import com.yapt.planttracker.domain.watering.WateringOverviewItem
import com.yapt.planttracker.domain.watering.WateringOverviewSection
import com.yapt.planttracker.ui.components.PlantPhoto
import com.yapt.planttracker.ui.util.labelRes
import com.yapt.planttracker.ui.util.relativeDateText

internal const val WATERING_OVERVIEW_COLLAPSED_ROWS = 5
private const val ROW_NAME_MAX_LINES = 2
private const val BAR_HEIGHT_DP = 12
private val BAND_ALPHAS = floatArrayOf(1f, 0.7f, 0.45f, 0.25f)

internal fun wateringRowTag(plantId: Long): String = "watering_overview_row_$plantId"

// The expanded groups survive rotation and process death; they are deliberately not persisted anywhere else.
private val ExpandedGroupsSaver = listSaver<MutableState<Set<String>>, String>(
    save = { it.value.toList() },
    restore = { mutableStateOf(it.toSet()) }
)

@Composable
internal fun WateringOverviewContent(
    overview: WateringOverview,
    onOpenPlant: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val expanded = rememberSaveable(saver = ExpandedGroupsSaver) { mutableStateOf(emptySet()) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "summary") { WateringSummary(overview) }
        overview.sections.forEach { section ->
            val groupKey = section.group.name
            val showAll = groupKey in expanded.value
            val visible = if (showAll) section.items else section.items.take(WATERING_OVERVIEW_COLLAPSED_ROWS)
            item(key = "header:$groupKey") { GroupHeader(section) }
            items(visible, key = { "$groupKey:${it.plant.id}" }) { WateringRow(it, onOpenPlant) }
            if (visible.size < section.items.size) {
                item(key = "showAll:$groupKey") {
                    TextButton(onClick = { expanded.value = expanded.value + groupKey }) {
                        Text(stringResource(R.string.watering_overview_show_all, section.items.size))
                    }
                }
            }
        }
    }
}

@Composable
private fun WateringSummary(overview: WateringOverview) {
    val separator = stringResource(R.string.watering_overview_count_separator)
    val parts = buildList {
        add(pluralStringResource(R.plurals.watering_overview_count_plants, overview.totalCount, overview.totalCount))
        if (overview.scheduledCount > 0) {
            add(stringResource(R.string.watering_overview_count_scheduled, overview.scheduledCount))
        }
        if (overview.restingCount > 0) {
            add(stringResource(R.string.watering_overview_count_resting, overview.restingCount))
        }
        if (overview.noScheduleCount > 0) {
            add(stringResource(R.string.watering_overview_count_no_schedule, overview.noScheduleCount))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = parts.joinToString(separator),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ProportionBar(overview)
    }
}

// Neutral tints of the theme's primary and outline colours, never the due-status colours: a long bar
// segment means "many plants on this rhythm", not "many plants overdue".
@Composable
private fun groupColor(group: WateringOverviewGroup): Color = when (group) {
    WateringOverviewGroup.EVERY_1_3_DAYS -> MaterialTheme.colorScheme.primary.copy(alpha = BAND_ALPHAS[0])
    WateringOverviewGroup.EVERY_4_7_DAYS -> MaterialTheme.colorScheme.primary.copy(alpha = BAND_ALPHAS[1])
    WateringOverviewGroup.EVERY_8_14_DAYS -> MaterialTheme.colorScheme.primary.copy(alpha = BAND_ALPHAS[2])
    WateringOverviewGroup.EVERY_15_PLUS_DAYS -> MaterialTheme.colorScheme.primary.copy(alpha = BAND_ALPHAS[3])
    WateringOverviewGroup.RESTING -> MaterialTheme.colorScheme.outline
    WateringOverviewGroup.NO_SCHEDULE -> MaterialTheme.colorScheme.outlineVariant
}

@Composable
private fun ProportionBar(overview: WateringOverview) {
    val resources = LocalContext.current.resources
    val counts = overview.sections.joinToString(
        resources.getString(R.string.watering_overview_bar_description_separator)
    ) {
        resources.getString(
            R.string.watering_overview_bar_description_item,
            resources.getString(it.group.labelRes()),
            it.items.size
        )
    }
    val description = stringResource(R.string.watering_overview_bar_description, counts)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(BAR_HEIGHT_DP.dp / 2))
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        overview.sections.forEach { section ->
            Box(
                modifier = Modifier
                    .weight(section.items.size.toFloat())
                    .fillMaxHeight()
                    .background(groupColor(section.group))
            )
        }
    }
}

@Composable
private fun GroupHeader(section: WateringOverviewSection) {
    Text(
        text = stringResource(
            R.string.watering_overview_header_with_count,
            stringResource(section.group.labelRes()),
            section.items.size
        ),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .semantics { heading() }
    )
}

@Composable
private fun WateringRow(item: WateringOverviewItem, onOpen: (Long) -> Unit) {
    val plant = item.plant
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onOpen(plant.id) }
                .testTag(wateringRowTag(plant.id))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.clearAndSetSemantics { }) {
                PlantPhoto(uri = plant.coverPhotoUri, size = 56.dp, rounded = true)
            }
            Spacer(Modifier.width(12.dp))
            WateringRowText(item)
        }
    }
}

@Composable
private fun RowScope.WateringRowText(item: WateringOverviewItem) {
    val plant = item.plant
    val room = plant.room?.takeIf { it.isNotBlank() }
    val statusLine = wateringStatusLine(item)
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = plant.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = ROW_NAME_MAX_LINES,
            overflow = TextOverflow.Ellipsis
        )
        if (room != null) {
            Text(
                text = room,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (statusLine != null) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun wateringStatusLine(item: WateringOverviewItem): String? {
    if (item.isPaused) return stringResource(R.string.watering_overview_paused)
    val interval = item.intervalDays?.let { pluralStringResource(R.plurals.watering_overview_every_days, it, it) }
    val next = item.nextDueAt?.let { relativeDateText(it) }
    return when {
        interval != null && next != null -> stringResource(R.string.watering_overview_interval_next, interval, next)
        interval != null -> interval
        next != null -> stringResource(R.string.watering_overview_next_only, next)
        else -> null
    }
}
