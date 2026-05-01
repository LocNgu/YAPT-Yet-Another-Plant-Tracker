package com.yapt.planttracker.data.repository

import com.yapt.planttracker.data.db.CareLogDao
import com.yapt.planttracker.data.entity.CareLogEntity
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.WateringFeedback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CareLogRepository(private val careLogDao: CareLogDao) {

    fun getLogsForPlant(plantId: Long): Flow<List<CareLog>> =
        careLogDao.getLogsForPlant(plantId).map { list -> list.map { it.toDomain() } }

    fun getPhotoLogsForPlant(plantId: Long): Flow<List<CareLog>> =
        careLogDao.getPhotoLogsForPlant(plantId).map { list -> list.map { it.toDomain() } }

    suspend fun getLastLogOfType(plantId: Long, careType: CareType): CareLog? =
        careLogDao.getLastLogOfType(plantId, careType.name)?.toDomain()

    suspend fun getLastTwoWaterings(plantId: Long): List<CareLog> =
        careLogDao.getLastTwoLogsOfType(plantId, CareType.WATER.name).map { it.toDomain() }

    suspend fun getCareLogCount(plantId: Long): Int =
        careLogDao.getCareLogCount(plantId)

    suspend fun addLog(log: CareLog): Long =
        careLogDao.insertLog(log.toEntity())

    suspend fun deleteLog(log: CareLog) =
        careLogDao.deleteLog(log.toEntity())
}

private fun CareLogEntity.toDomain() = CareLog(
    id = id,
    plantId = plantId,
    careType = runCatching { CareType.valueOf(careType) }.getOrDefault(CareType.NOTE),
    loggedAt = loggedAt,
    notes = notes,
    photoUri = photoUri,
    amount = amount,
    wateringFeedback = wateringFeedback?.let { runCatching { WateringFeedback.valueOf(it) }.getOrNull() }
)

private fun CareLog.toEntity() = CareLogEntity(
    id = id,
    plantId = plantId,
    careType = careType.name,
    loggedAt = loggedAt,
    notes = notes,
    photoUri = photoUri,
    amount = amount,
    wateringFeedback = wateringFeedback?.name
)
