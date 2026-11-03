package com.yapt.planttracker.ui.screens.away

import com.yapt.planttracker.domain.away.VisitProjection
import com.yapt.planttracker.domain.away.roomOrNull
import com.yapt.planttracker.domain.model.Plant

/** The room-filter key for plants with no room; a leading space can never equal a trimmed room name. */
internal const val NO_ROOM_FILTER = " no room"

internal data class SitterRoomChip(val key: String, val label: String)

internal data class SitterPickerRooms(val chips: List<SitterRoomChip>, val hasNoRoom: Boolean) {
    /** [filter] when a chip for it exists, else `null` (All), so a room that vanished never leaves an empty list. */
    fun resolve(filter: String?): String? = filter?.takeIf { key ->
        if (key == NO_ROOM_FILTER) hasNoRoom else chips.any { it.key == key }
    }
}

/** Case-insensitive room key, matching how the sitter list groups rooms; [NO_ROOM_FILTER] when unset or blank. */
internal fun Plant.roomFilterKey(): String = roomOrNull()?.lowercase() ?: NO_ROOM_FILTER

/** Every plant the picker can show, whichever section it starts in. */
internal fun VisitProjection.pickerPlants(): List<Plant> =
    listed.map { it.plant } + notListed.map { it.plant } + resting

internal fun sitterPickerRooms(projection: VisitProjection): SitterPickerRooms {
    val plants = projection.pickerPlants()
    val chips = plants
        .mapNotNull { plant -> plant.roomOrNull() }
        .groupBy { it.lowercase() }
        .map { (key, names) -> SitterRoomChip(key, names.first()) }
        .sortedBy { it.key }
    return SitterPickerRooms(chips, hasNoRoom = plants.any { it.roomOrNull() == null })
}

/** Room keys holding at least one ticked plant, across all rooms whatever the filter is. */
internal fun sitterRoomsWithSelection(selected: VisitProjection): Set<String> =
    selected.listed.mapTo(HashSet()) { it.plant.roomFilterKey() }

internal fun Plant.inRoomFilter(filter: String?): Boolean = filter == null || roomFilterKey() == filter
