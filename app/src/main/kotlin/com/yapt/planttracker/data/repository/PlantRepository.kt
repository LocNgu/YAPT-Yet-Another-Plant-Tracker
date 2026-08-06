package com.yapt.planttracker.data.repository

import com.yapt.planttracker.data.db.PlantDao
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.domain.model.Plant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlantRepository(private val plantDao: PlantDao) {

    fun getAllPlants(): Flow<List<Plant>> =
        plantDao.getAllPlants().map { list -> list.map { it.toDomain() } }

    fun getPlantById(id: Long): Flow<Plant?> =
        plantDao.getPlantById(id).map { it?.toDomain() }

    fun getAllRooms(): Flow<List<String>> = plantDao.getAllRooms()

    suspend fun addPlant(plant: Plant): Long =
        plantDao.insertPlant(plant.toEntity())

    suspend fun updatePlant(plant: Plant) =
        plantDao.updatePlant(plant.toEntity())

    suspend fun deletePlant(plant: Plant) =
        plantDao.deletePlant(plant.toEntity())

    fun getArchivedPlants(): Flow<List<Plant>> =
        plantDao.getArchivedPlants().map { list -> list.map { it.toDomain() } }

    fun getArchivedCount(): Flow<Int> = plantDao.getArchivedCount()

    suspend fun archivePlant(id: Long, timestamp: Long = System.currentTimeMillis()) =
        plantDao.archivePlant(id, timestamp)

    suspend fun restorePlant(id: Long) = plantDao.restorePlant(id)

    /** Archives every id in a single atomic statement (bulk graveyard action, #448). */
    suspend fun archivePlants(ids: List<Long>, timestamp: Long = System.currentTimeMillis()) =
        plantDao.archivePlants(ids, timestamp)

    /** Restores every id in a single atomic statement (bulk-archive undo, #448). */
    suspend fun restorePlants(ids: List<Long>) = plantDao.restorePlants(ids)

    suspend fun deleteAllArchived() = plantDao.deleteAllArchived()
}

private fun PlantEntity.toDomain() = Plant(
    id = id,
    name = name,
    species = species,
    room = room,
    coverPhotoUri = coverPhotoUri,
    notes = notes,
    wateringIntervalDays = wateringIntervalDays,
    fertilizingIntervalDays = fertilizingIntervalDays,
    repottingIntervalDays = repottingIntervalDays,
    createdAt = createdAt,
    updatedAt = updatedAt,
    wateringDueDateOverride = wateringDueDateOverride,
    useLiquidFertilizer = useLiquidFertilizer,
    archivedAt = archivedAt
)

private fun Plant.toEntity() = PlantEntity(
    id = id,
    name = name,
    species = species,
    room = room,
    coverPhotoUri = coverPhotoUri,
    notes = notes,
    wateringIntervalDays = wateringIntervalDays,
    fertilizingIntervalDays = fertilizingIntervalDays,
    repottingIntervalDays = repottingIntervalDays,
    createdAt = createdAt,
    updatedAt = updatedAt,
    wateringDueDateOverride = wateringDueDateOverride,
    useLiquidFertilizer = useLiquidFertilizer,
    archivedAt = archivedAt
)
