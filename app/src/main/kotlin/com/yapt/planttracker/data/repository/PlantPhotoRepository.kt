package com.yapt.planttracker.data.repository

import com.yapt.planttracker.data.db.PlantPhotoDao
import com.yapt.planttracker.data.entity.PlantPhotoEntity
import com.yapt.planttracker.domain.model.PlantPhoto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlantPhotoRepository(private val plantPhotoDao: PlantPhotoDao) {

    fun getPhotosForPlant(plantId: Long): Flow<List<PlantPhoto>> =
        plantPhotoDao.getPhotosForPlant(plantId).map { list -> list.map { it.toDomain() } }

    suspend fun addPhoto(photo: PlantPhoto): Long =
        plantPhotoDao.insertPhoto(photo.toEntity())

    suspend fun addPhotos(photos: List<PlantPhoto>) {
        plantPhotoDao.insertAll(photos.map { it.toEntity() })
    }

    suspend fun getPhotosForPlantOnce(plantId: Long): List<PlantPhoto> =
        plantPhotoDao.getPhotosForPlantOnce(plantId).map { it.toDomain() }

    suspend fun deletePhoto(photo: PlantPhoto) =
        plantPhotoDao.deletePhoto(photo.toEntity())
}

private fun PlantPhotoEntity.toDomain() = PlantPhoto(
    id = id,
    plantId = plantId,
    uri = uri,
    capturedAt = capturedAt
)

private fun PlantPhoto.toEntity() = PlantPhotoEntity(
    id = id,
    plantId = plantId,
    uri = uri,
    capturedAt = capturedAt
)
