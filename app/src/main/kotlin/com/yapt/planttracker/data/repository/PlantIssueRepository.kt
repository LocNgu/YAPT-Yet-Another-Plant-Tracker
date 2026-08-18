package com.yapt.planttracker.data.repository

import com.yapt.planttracker.data.db.PlantIssueDao
import com.yapt.planttracker.data.entity.PlantIssueEntity
import com.yapt.planttracker.domain.model.PlantIssue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlantIssueRepository(private val plantIssueDao: PlantIssueDao) {

    fun getIssuesForPlant(plantId: Long): Flow<List<PlantIssue>> =
        plantIssueDao.getIssuesForPlant(plantId).map { list -> list.map { it.toDomain() } }

    fun getActiveIssuesForPlant(plantId: Long): Flow<List<PlantIssue>> =
        plantIssueDao.getActiveIssuesForPlant(plantId).map { list -> list.map { it.toDomain() } }

    suspend fun getActiveIssueCountForPlant(plantId: Long): Int =
        plantIssueDao.getActiveIssueCountForPlant(plantId)

    suspend fun getIssueById(id: Long): PlantIssue? =
        plantIssueDao.getIssueById(id)?.toDomain()

    suspend fun addIssue(issue: PlantIssue): Long =
        plantIssueDao.insertIssue(issue.toEntity())

    suspend fun updateIssue(issue: PlantIssue) =
        plantIssueDao.updateIssue(issue.toEntity())

    suspend fun deleteIssue(issue: PlantIssue) =
        plantIssueDao.deleteIssue(issue.toEntity())
}

private fun PlantIssueEntity.toDomain() = PlantIssue(
    id = id,
    plantId = plantId,
    name = name,
    startedAt = startedAt,
    resolvedAt = resolvedAt,
    resolutionNote = resolutionNote,
    linkedReminderId = linkedReminderId
)

private fun PlantIssue.toEntity() = PlantIssueEntity(
    id = id,
    plantId = plantId,
    name = name,
    startedAt = startedAt,
    resolvedAt = resolvedAt,
    resolutionNote = resolutionNote,
    linkedReminderId = linkedReminderId
)
