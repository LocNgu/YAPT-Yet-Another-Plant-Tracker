package com.yapt.planttracker.data.backup

import com.yapt.planttracker.data.db.SitterVisitWithPlants
import com.yapt.planttracker.data.entity.AwayPeriodEntity
import com.yapt.planttracker.data.entity.SitterVisitEntity
import com.yapt.planttracker.data.entity.SitterVisitPlantEntity
import java.time.LocalDate

internal data class AwayRestoreRows(
    val periods: List<AwayPeriodEntity>,
    val visits: List<SitterVisitEntity>,
    val visitPlants: List<SitterVisitPlantEntity>
)

/**
 * Away mode rows ↔ backup schema v22 (#896). Days travel as ISO-8601 dates. Restore is lenient: a trip
 * or visit whose dates don't parse (or a reversed trip) is skipped rather than failing the restore,
 * visits are re-filtered into their trip, and visit plants whose plant isn't in the backup are dropped.
 */
internal object AwayBackupMapping {

    fun toBackup(periods: List<AwayPeriodEntity>, visits: List<SitterVisitWithPlants>): List<BackupAwayPeriod> {
        val visitsByPeriod = visits.groupBy { it.visit.awayPeriodId }
        return periods.map { period ->
            BackupAwayPeriod(
                id = period.id,
                firstDay = LocalDate.ofEpochDay(period.firstDay).toString(),
                lastDay = LocalDate.ofEpochDay(period.lastDay).toString(),
                visitEveryDays = period.visitEveryDays,
                createdAt = period.createdAt,
                visits = visitsByPeriod[period.id].orEmpty().map { it.toBackup() }
            )
        }
    }

    fun fromBackup(periods: List<BackupAwayPeriod>, plantIds: Set<Long>): AwayRestoreRows {
        val restoredPeriods = periods.distinctBy { it.id }
            .mapNotNull { period -> period.toEntityOrNull()?.let { period to it } }
        val restoredVisits = restoredPeriods
            .flatMap { (period, row) ->
                period.visits.mapNotNull { visit -> visit.toEntityOrNull(row)?.let { visit to it } }
            }
            .distinctBy { (_, row) -> row.id }
        val visitPlants = restoredVisits.flatMap { (visit, row) ->
            visit.plants
                .filter { it.plantId in plantIds }
                .distinctBy { it.plantId }
                .map { SitterVisitPlantEntity(visitId = row.id, plantId = it.plantId, watered = it.watered) }
        }
        return AwayRestoreRows(restoredPeriods.map { it.second }, restoredVisits.map { it.second }, visitPlants)
    }

    private fun BackupAwayPeriod.toEntityOrNull(): AwayPeriodEntity? {
        val first = parseDay(firstDay) ?: return null
        val last = parseDay(lastDay)?.takeUnless { it.isBefore(first) } ?: return null
        return AwayPeriodEntity(
            id = id,
            firstDay = first.toEpochDay(),
            lastDay = last.toEpochDay(),
            visitEveryDays = visitEveryDays,
            createdAt = createdAt
        )
    }

    private fun BackupSitterVisit.toEntityOrNull(period: AwayPeriodEntity): SitterVisitEntity? {
        val epochDay = parseDay(day)?.toEpochDay()?.takeIf { it in period.firstDay..period.lastDay } ?: return null
        return SitterVisitEntity(
            id = id,
            awayPeriodId = period.id,
            day = epochDay,
            state = state,
            createdAt = createdAt
        )
    }

    private fun parseDay(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

    private fun SitterVisitWithPlants.toBackup() = BackupSitterVisit(
        id = visit.id,
        day = LocalDate.ofEpochDay(visit.day).toString(),
        state = visit.state,
        createdAt = visit.createdAt,
        plants = plants.sortedBy { it.plantId }
            .map { BackupSitterVisitPlant(plantId = it.plantId, watered = it.watered) }
    )
}
