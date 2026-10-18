package com.yapt.planttracker.data.repository

import com.yapt.planttracker.data.db.AwayPeriodDao
import com.yapt.planttracker.data.db.AwayPeriodWithVisitsRelation
import com.yapt.planttracker.data.db.SitterVisitWithPlants
import com.yapt.planttracker.data.entity.AwayPeriodEntity
import com.yapt.planttracker.data.entity.SitterVisitEntity
import com.yapt.planttracker.data.entity.SitterVisitPlantEntity
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.AwayPeriodWithVisits
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitPlant
import com.yapt.planttracker.domain.model.SitterVisitState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

@Suppress("TooManyFunctions")
class AwayPeriodRepository(private val awayPeriodDao: AwayPeriodDao) {

    fun observeAll(): Flow<List<AwayPeriod>> =
        awayPeriodDao.observeAllPeriods().map { list -> list.map { it.toDomain() } }

    fun observeAllWithVisits(): Flow<List<AwayPeriodWithVisits>> =
        awayPeriodDao.observeAllWithVisits().map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<AwayPeriod> =
        awayPeriodDao.getAllPeriods().map { it.toDomain() }

    suspend fun getAllWithVisits(): List<AwayPeriodWithVisits> =
        awayPeriodDao.getAllWithVisits().map { it.toDomain() }

    suspend fun addPeriod(period: AwayPeriod): Long =
        awayPeriodDao.insertPeriod(period.toEntity())

    suspend fun updatePeriod(period: AwayPeriod) =
        awayPeriodDao.updatePeriod(period.toEntity())

    suspend fun updatePeriodAndDeleteVisits(period: AwayPeriod, visitIds: List<Long>) =
        awayPeriodDao.updatePeriodAndDeleteVisits(period.toEntity(), visitIds)

    suspend fun deletePeriod(period: AwayPeriod) =
        awayPeriodDao.deletePeriod(period.toEntity())

    suspend fun addVisit(visit: SitterVisit): Long =
        awayPeriodDao.insertVisitWithPlants(visit.toEntity(), visit.plants.map { it.toEntity(visit.id) })

    suspend fun updateVisit(visit: SitterVisit) =
        awayPeriodDao.replaceVisitPlants(visit.toEntity(), visit.plants.map { it.toEntity(visit.id) })

    suspend fun deleteVisits(ids: List<Long>) {
        if (ids.isNotEmpty()) awayPeriodDao.deleteVisits(ids)
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
    plants = plants.sortedBy { it.plantId }.map { SitterVisitPlant(it.plantId, it.watered) },
    createdAt = visit.createdAt
)

private fun AwayPeriodWithVisitsRelation.toDomain() = AwayPeriodWithVisits(
    period = period.toDomain(),
    visits = visits.sortedWith(compareBy({ it.visit.day }, { it.visit.id })).map { it.toDomain() }
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
