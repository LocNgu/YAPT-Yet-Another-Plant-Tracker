package com.yapt.planttracker.data.repository

import com.yapt.planttracker.data.db.PlantPhotoDao
import com.yapt.planttracker.data.entity.PlantPhotoEntity
import com.yapt.planttracker.domain.model.PlantPhoto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [onPhotoReferencesRemoved] fires after [deletePhoto] — see [com.yapt.planttracker.YaptApplication
 * .scheduleOrphanPhotoCleanup] (#736/#559). Defaulted to a no-op so every pre-existing direct
 * construction (tests, `DemoDataSeeder`) still compiles unchanged.
 */
class PlantPhotoRepository(
    private val plantPhotoDao: PlantPhotoDao,
    private val onPhotoReferencesRemoved: () -> Unit = {}
) {

    fun getPhotosForPlant(plantId: Long): Flow<List<PlantPhoto>> =
        plantPhotoDao.getPhotosForPlant(plantId).map { list -> list.map { it.toDomain() } }

    suspend fun addPhoto(photo: PlantPhoto): Long =
        plantPhotoDao.insertPhoto(photo.toEntity())

    suspend fun addPhotos(photos: List<PlantPhoto>) {
        plantPhotoDao.insertAll(photos.map { it.toEntity() })
    }

    suspend fun getPhotosForPlantOnce(plantId: Long): List<PlantPhoto> =
        plantPhotoDao.getPhotosForPlantOnce(plantId).map { it.toDomain() }

    suspend fun deletePhoto(photo: PlantPhoto) {
        plantPhotoDao.deletePhoto(photo.toEntity())
        onPhotoReferencesRemoved()
    }
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
