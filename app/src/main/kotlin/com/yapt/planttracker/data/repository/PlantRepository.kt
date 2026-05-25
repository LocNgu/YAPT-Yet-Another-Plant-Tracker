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
    createdAt = createdAt,
    updatedAt = updatedAt,
    wateringDueDateOverride = wateringDueDateOverride,
    useLiquidFertilizer = useLiquidFertilizer
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
    createdAt = createdAt,
    updatedAt = updatedAt,
    wateringDueDateOverride = wateringDueDateOverride,
    useLiquidFertilizer = useLiquidFertilizer
)
