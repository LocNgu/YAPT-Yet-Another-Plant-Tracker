package com.yapt.planttracker.ui.screens.away

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.away.VisitProjection
import com.yapt.planttracker.domain.away.roomOrNull
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.ui.components.CareGroupHeader
import com.yapt.planttracker.ui.components.PlantCardLayout
import com.yapt.planttracker.ui.components.RoomFilterChip
import com.yapt.planttracker.ui.components.yaptFilterChipColors
import com.yapt.planttracker.ui.util.relativeDateText
import com.yapt.planttracker.util.toStartOfDayMillis
import java.time.LocalDate

internal enum class SitterSection { LISTED, NOT_LISTED, RESTING }

internal class SitterSectionExpansion(
    private val listed: Boolean,
    private val notListed: Boolean,
    private val resting: Boolean,
    val toggle: (SitterSection) -> Unit
) {
    fun isExpanded(section: SitterSection): Boolean = when (section) {
        SitterSection.LISTED -> listed
        SitterSection.NOT_LISTED -> notListed
        SitterSection.RESTING -> resting
    }
}

@Composable
internal fun rememberSitterSectionExpansion(): SitterSectionExpansion {
    var listed by rememberSaveable { mutableStateOf(true) }
    var notListed by rememberSaveable { mutableStateOf(false) }
    var resting by rememberSaveable { mutableStateOf(false) }
    return SitterSectionExpansion(listed, notListed, resting) { section ->
        when (section) {
            SitterSection.LISTED -> listed = !listed
            SitterSection.NOT_LISTED -> notListed = !notListed
            SitterSection.RESTING -> resting = !resting
        }
    }
}

internal class SitterPickerCallbacks(
    val onSelectRoom: (String?) -> Unit,
    val onTogglePlant: (Long) -> Unit
)

private class PickerRow(val plant: Plant, val dueDate: LocalDate?, val missed: Boolean, val longer: Boolean)

private class PickerSection(
    val section: SitterSection,
    val title: Int,
    val hint: Int?,
    val rows: List<PickerRow>
)

private val PICKER_EDGE_PADDING = 16.dp

/** Room chips and the three collapsible sections; built from the raw suggestion so rows never move on a tick. */
@Suppress("LongParameterList")
internal fun LazyListScope.sitterPicker(
    projection: VisitProjection,
    selected: VisitProjection,
    roomFilter: String?,
    expansion: SitterSectionExpansion,
    callbacks: SitterPickerCallbacks
) {
    val rooms = sitterPickerRooms(projection)
    val activeFilter = rooms.resolve(roomFilter)
    if (rooms.chips.isNotEmpty() || rooms.hasNoRoom) {
        item(key = "rooms") {
            RoomChips(rooms, activeFilter, sitterRoomsWithSelection(selected), callbacks.onSelectRoom)
        }
    }
    val tickedIds = selected.listed.mapTo(HashSet()) { it.plant.id }
    val sections = listOf(
        PickerSection(
            SitterSection.LISTED,
            R.string.sitter_pick_section_listed,
            null,
            projection.listed.map { PickerRow(it.plant, it.dueDate, it.missedLastTime, it.longerThanUsual) }
        ),
        PickerSection(
            SitterSection.NOT_LISTED,
            R.string.sitter_pick_section_not_listed,
            null,
            projection.notListed.map { PickerRow(it.plant, it.dueDate, it.missedLastTime, it.longerThanUsual) }
        ),
        PickerSection(
            SitterSection.RESTING,
            R.string.sitter_pick_section_resting,
            R.string.sitter_pick_resting_hint,
            projection.resting.map { PickerRow(it, null, missed = false, longer = false) }
        )
    )
    for (section in sections) {
        val rows = section.rows.filter { it.plant.inRoomFilter(activeFilter) }
        if (rows.isEmpty()) continue
        pickerSection(section, rows, expansion, tickedIds, callbacks.onTogglePlant)
    }
}

private fun LazyListScope.pickerSection(
    section: PickerSection,
    rows: List<PickerRow>,
    expansion: SitterSectionExpansion,
    tickedIds: Set<Long>,
    onTogglePlant: (Long) -> Unit
) {
    val expanded = expansion.isExpanded(section.section)
    item(key = "header-${section.section}") {
        Column(modifier = Modifier.padding(horizontal = PICKER_EDGE_PADDING)) {
            CareGroupHeader(
                title = stringResource(section.title),
                plantCount = rows.size,
                collapsed = !expanded,
                onToggle = { expansion.toggle(section.section) }
            )
            if (section.hint != null) {
                Text(
                    stringResource(section.hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (expanded) {
        items(rows, key = { "${section.section}-${it.plant.id}" }) { row ->
            PickerCard(row, selected = row.plant.id in tickedIds, onToggle = { onTogglePlant(row.plant.id) })
        }
    }
}

@Composable
private fun RoomChips(
    rooms: SitterPickerRooms,
    activeFilter: String?,
    roomsWithSelection: Set<String>,
    onSelectRoom: (String?) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PICKER_EDGE_PADDING, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = activeFilter == null,
                onClick = { onSelectRoom(null) },
                label = { Text(stringResource(R.string.time_range_all)) },
                colors = yaptFilterChipColors()
            )
        }
        items(rooms.chips, key = { "room-${it.key}" }) { chip ->
            RoomFilterChip(
                label = chip.label,
                selected = activeFilter == chip.key,
                containsSelection = chip.key in roomsWithSelection,
                onClick = { onSelectRoom(chip.key) }
            )
        }
        if (rooms.hasNoRoom) {
            item {
                RoomFilterChip(
                    label = stringResource(R.string.sitter_room_none),
                    selected = activeFilter == NO_ROOM_FILTER,
                    containsSelection = NO_ROOM_FILTER in roomsWithSelection,
                    onClick = { onSelectRoom(NO_ROOM_FILTER) }
                )
            }
        }
    }
}

@Composable
private fun PickerCard(row: PickerRow, selected: Boolean, onToggle: () -> Unit) {
    PlantCardLayout(
        name = row.plant.name,
        species = row.plant.species,
        photoUri = row.plant.coverPhotoUri,
        onClick = onToggle,
        selectionMode = true,
        selected = selected,
        onLongClick = null,
        onToggleSelect = onToggle,
        statusLine = { PickerStatusLine(row) }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerStatusLine(row: PickerRow) {
    val dateText = if (row.dueDate != null) {
        relativeDateText(row.dueDate.toStartOfDayMillis())
    } else {
        stringResource(R.string.date_group_dormant)
    }
    val line = listOfNotNull(row.plant.roomOrNull(), dateText)
        .joinToString(stringResource(R.string.sitter_plan_rooms_separator))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (row.missed || row.longer) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (row.missed) {
                    PickerTag(
                        stringResource(R.string.sitter_missed_tag),
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                if (row.longer) {
                    PickerTag(
                        stringResource(R.string.sitter_pick_longer_tag),
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerTag(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
