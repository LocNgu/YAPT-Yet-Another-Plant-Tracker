package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.Plant

data class RoomCount(val room: String?, val count: Int)

/**
 * The plan screen's summary card (product ADR-0065): how many plants and where, and the four things worth a
 * second look before sharing. Every list is of listed plants, by name.
 */
data class VisitPlanSummary(
    val plantCount: Int,
    val roomCounts: List<RoomCount>,
    val restingCount: Int,
    val missedLastTime: List<Plant>,
    val longerThanUsual: List<ProjectedPlant>,
    val noPhoto: List<Plant>,
    val lookAlikes: List<Plant>
)

fun VisitProjection.summary(): VisitPlanSummary {
    val byName = compareBy<ProjectedPlant>({ it.plant.name.lowercase() }, { it.plant.id })
    val sorted = listed.sortedWith(byName)
    val noPhoto = sorted.filter { it.plant.coverPhotoUri == null }
    val sameNameAndRoom = sorted.groupBy { it.plant.lookAlikeKey() }
    return VisitPlanSummary(
        plantCount = listed.size,
        roomCounts = SitterListComposer.compose(this).rooms.map { RoomCount(it.room, it.plants.size) },
        restingCount = resting.size,
        missedLastTime = sorted.filter { it.missedLastTime }.map { it.plant },
        longerThanUsual = sorted.filter { it.longerThanUsual },
        noPhoto = noPhoto.map { it.plant },
        lookAlikes = noPhoto
            .filter { sameNameAndRoom.getValue(it.plant.lookAlikeKey()).size > 1 }
            .map { it.plant }
    )
}

private fun Plant.lookAlikeKey(): Pair<String, String?> = name.trim().lowercase() to roomOrNull()?.lowercase()
