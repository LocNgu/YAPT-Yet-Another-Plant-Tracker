package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.Plant
import java.time.LocalDate

/** A plant's room, or `null` for "No room" (blank counts as none). */
internal fun Plant.roomOrNull(): String? = room?.trim()?.takeIf { it.isNotEmpty() }

data class SitterListPlant(
    val plantId: Long,
    val name: String,
    /** Shown only when set and different from [name]. */
    val species: String?,
    val coverPhotoUri: String?,
    val missedLastTime: Boolean
)

data class SitterListRoom(val room: String?, val plants: List<SitterListPlant>)

/**
 * The structure of what a sitter receives for one visit (product ADR-0065): plants grouped by room
 * (alphabetical, "No room" last) then by name, and the resting plants to leave dry. The PDF and text
 * renderers only lay this out, the same split as `ReminderNotificationComposer` and `ReminderWorker`.
 */
data class SitterList(
    val visitDay: LocalDate,
    val rooms: List<SitterListRoom>,
    val restingRooms: List<SitterListRoom>
) {
    val plantCount: Int get() = rooms.sumOf { it.plants.size }
    val restingCount: Int get() = restingRooms.sumOf { it.plants.size }
}

object SitterListComposer {

    fun compose(projection: VisitProjection): SitterList = SitterList(
        visitDay = projection.visitDay,
        rooms = groupByRoom(projection.listed.map { it.plant to it.missedLastTime }),
        restingRooms = groupByRoom(projection.resting.map { it to false })
    )

    private fun groupByRoom(plants: List<Pair<Plant, Boolean>>): List<SitterListRoom> = plants
        .groupBy { (plant, _) -> plant.roomOrNull()?.lowercase() }
        .values
        .map { group ->
            SitterListRoom(
                room = group.first().first.roomOrNull(),
                plants = group
                    .sortedWith(compareBy({ it.first.name.lowercase() }, { it.first.id }))
                    .map { (plant, missed) -> plant.toListPlant(missed) }
            )
        }
        .sortedWith(compareBy<SitterListRoom> { it.room == null }.thenBy { it.room?.lowercase().orEmpty() })

    private fun Plant.toListPlant(missedLastTime: Boolean) = SitterListPlant(
        plantId = id,
        name = name,
        species = species?.trim()?.takeIf { it.isNotEmpty() && !it.equals(name.trim(), ignoreCase = true) },
        coverPhotoUri = coverPhotoUri,
        missedLastTime = missedLastTime
    )
}
