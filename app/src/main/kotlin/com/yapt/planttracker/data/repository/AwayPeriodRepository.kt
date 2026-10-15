package com.yapt.planttracker.data.repository

import com.yapt.planttracker.data.db.AwayPeriodDao
import com.yapt.planttracker.data.db.SitterVisitDao
import com.yapt.planttracker.data.db.SitterVisitWithPlants
import com.yapt.planttracker.data.entity.AwayPeriodEntity
import com.yapt.planttracker.data.entity.SitterVisitEntity
import com.yapt.planttracker.data.entity.SitterVisitPlantEntity
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitPlant
import com.yapt.planttracker.domain.model.SitterVisitState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** Away mode trips and their sitter visits (#895, technical ADR-0038). */
class AwayPeriodRepository(
    private val awayPeriodDao: AwayPeriodDao,
    private val sitterVisitDao: SitterVisitDao
) {

    /** Every trip, past ones included, ordered by first day. */
    fun observeAll(): Flow<List<AwayPeriod>> =
        awayPeriodDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<AwayPeriod> = awayPeriodDao.getAll().map { it.toDomain() }

    suspend fun add(period: AwayPeriod): Long = awayPeriodDao.insert(period.toEntity())

    /**
     * Saves [period]'s new dates and deletes [visitsOutside] (the visits those dates left outside the
     * trip — see `AwayPeriods.visitsOutside`) in one transaction. Logs those visits wrote stay.
     */
    suspend fun update(period: AwayPeriod, visitsOutside: List<SitterVisit> = emptyList()) =
        awayPeriodDao.updateAndDeleteVisits(period.toEntity(), visitsOutside.map { it.id })

    /** Removes the trip together with its visits (cascade). */
    suspend fun delete(period: AwayPeriod) = awayPeriodDao.delete(period.toEntity())

    fun observeVisits(awayPeriodId: Long): Flow<List<SitterVisit>> =
        sitterVisitDao.observeVisits(awayPeriodId).map { list -> list.map { it.toDomain() } }

    suspend fun getVisits(awayPeriodId: Long): List<SitterVisit> =
        sitterVisitDao.getVisits(awayPeriodId).map { it.toDomain() }

    /** Inserts (id 0) or updates [visit], replacing its whole plant list; returns the visit's id. */
    suspend fun saveVisit(visit: SitterVisit): Long =
        sitterVisitDao.saveVisit(visit.toEntity(), visit.plants.map { it.toEntity(visit.id) })

    suspend fun deleteVisits(visits: List<SitterVisit>) {
        if (visits.isNotEmpty()) awayPeriodDao.deleteVisits(visits.map { it.id })
    }
}

private fun AwayPeriodEntity.toDomain() = AwayPeriod(
    id = id,
    firstDay = LocalDate.ofEpochDay(firstDay),
    lastDay = LocalDate.ofEpochDay(lastDay),
    visitEveryDays = visitEveryDays,
    createdAt = createdAt
)

private fun AwayPeriod.toEntity() = AwayPeriodEntity(
    id = id,
    firstDay = firstDay.toEpochDay(),
    lastDay = lastDay.toEpochDay(),
    visitEveryDays = visitEveryDays,
    createdAt = createdAt
)

private fun SitterVisitWithPlants.toDomain() = SitterVisit(
    id = visit.id,
    awayPeriodId = visit.awayPeriodId,
    day = LocalDate.ofEpochDay(visit.day),
    state = runCatching { SitterVisitState.valueOf(visit.state) }.getOrDefault(SitterVisitState.PLANNED),
    plants = plants.sortedBy { it.plantId }.map { SitterVisitPlant(plantId = it.plantId, watered = it.watered) },
    createdAt = visit.createdAt
)

private fun SitterVisit.toEntity() = SitterVisitEntity(
    id = id,
    awayPeriodId = awayPeriodId,
    day = day.toEpochDay(),
    state = state.name,
    createdAt = createdAt
)

private fun SitterVisitPlant.toEntity(visitId: Long) = SitterVisitPlantEntity(
    visitId = visitId,
    plantId = plantId,
    watered = watered
)
