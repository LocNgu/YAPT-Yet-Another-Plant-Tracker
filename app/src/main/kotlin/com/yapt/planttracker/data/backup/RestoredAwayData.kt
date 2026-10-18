package com.yapt.planttracker.data.backup

import com.yapt.planttracker.data.entity.AwayPeriodEntity
import com.yapt.planttracker.data.entity.SitterVisitEntity
import com.yapt.planttracker.data.entity.SitterVisitPlantEntity
import com.yapt.planttracker.domain.model.SitterVisitState
import java.time.LocalDate

internal class RestoredAwayData(
    val periods: List<AwayPeriodEntity>,
    val visits: List<SitterVisitEntity>,
    val visitPlants: List<SitterVisitPlantEntity>
)

internal fun buildBackupAwayPeriods(
    periods: List<AwayPeriodEntity>,
    visits: List<SitterVisitEntity>,
    visitPlants: List<SitterVisitPlantEntity>
): List<BackupAwayPeriod> {
    val visitsByPeriod = visits.groupBy { it.awayPeriodId }
    val plantsByVisit = visitPlants.groupBy { it.visitId }
    return periods.map { period ->
        BackupAwayPeriod(
            id = period.id,
            firstDay = LocalDate.ofEpochDay(period.firstDay).toString(),
            lastDay = LocalDate.ofEpochDay(period.lastDay).toString(),
            visitEveryDays = period.visitEveryDays,
            createdAt = period.createdAt,
            visits = visitsByPeriod[period.id].orEmpty().map { visit ->
                BackupSitterVisit(
                    id = visit.id,
                    day = LocalDate.ofEpochDay(visit.day).toString(),
                    state = visit.state,
                    createdAt = visit.createdAt,
                    plants = plantsByVisit[visit.id].orEmpty().map {
                        BackupSitterVisitPlant(plantId = it.plantId, watered = it.watered)
                    }
                )
            }
        )
    }
}

/**
 * A trip or visit with an unparseable date is skipped rather than failing the restore. Visits are
 * re-filtered into their trip, visit plants whose plant isn't in the backup are dropped, and repeated ids
 * collapse to the first, so no insert can violate a key. Plant ids are restored verbatim, so
 * [restoredPlantIds] is the id set of the plants being restored.
 */
internal fun List<BackupAwayPeriod>.toRestoredAwayData(restoredPlantIds: Set<Long>): RestoredAwayData {
    val periods = mutableListOf<AwayPeriodEntity>()
    val visits = mutableListOf<SitterVisitEntity>()
    val visitPlants = mutableListOf<SitterVisitPlantEntity>()
    val seenPeriodIds = mutableSetOf<Long>()
    val seenVisitIds = mutableSetOf<Long>()
    for (backupPeriod in this) {
        val firstDay = parseIsoDate(backupPeriod.firstDay)
        val lastDay = parseIsoDate(backupPeriod.lastDay)
        if (firstDay == null || lastDay == null || !seenPeriodIds.add(backupPeriod.id)) continue
        periods += AwayPeriodEntity(
            id = backupPeriod.id,
            firstDay = firstDay.toEpochDay(),
            lastDay = lastDay.toEpochDay(),
            visitEveryDays = backupPeriod.visitEveryDays,
            createdAt = backupPeriod.createdAt
        )
        for (backupVisit in backupPeriod.visits) {
            val day = parseIsoDate(backupVisit.day)
            if (day == null || day !in firstDay..lastDay || !seenVisitIds.add(backupVisit.id)) continue
            visits += SitterVisitEntity(
                id = backupVisit.id,
                awayPeriodId = backupPeriod.id,
                day = day.toEpochDay(),
                state = runCatching { SitterVisitState.valueOf(backupVisit.state) }
                    .getOrDefault(SitterVisitState.PLANNED).name,
                createdAt = backupVisit.createdAt
            )
            backupVisit.plants
                .filter { it.plantId in restoredPlantIds }
                .distinctBy { it.plantId }
                .mapTo(visitPlants) { SitterVisitPlantEntity(backupVisit.id, it.plantId, it.watered) }
        }
    }
    return RestoredAwayData(periods, visits, visitPlants)
}

private fun parseIsoDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
