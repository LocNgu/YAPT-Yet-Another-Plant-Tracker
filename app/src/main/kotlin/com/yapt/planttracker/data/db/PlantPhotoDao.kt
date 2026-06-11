package com.yapt.planttracker.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yapt.planttracker.data.entity.PlantPhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlantPhotoDao {
    @Query("SELECT * FROM plant_photos WHERE plantId = :plantId ORDER BY capturedAt DESC")
    fun getPhotosForPlant(plantId: Long): Flow<List<PlantPhotoEntity>>

    @Query("SELECT * FROM plant_photos")
    fun getAllPhotos(): Flow<List<PlantPhotoEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPhoto(photo: PlantPhotoEntity): Long

    @Delete
    suspend fun deletePhoto(photo: PlantPhotoEntity)

    @Query("SELECT * FROM plant_photos WHERE plantId = :plantId ORDER BY capturedAt DESC")
    suspend fun getPhotosForPlantOnce(plantId: Long): List<PlantPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(photos: List<PlantPhotoEntity>): List<Long>

    @Query("DELETE FROM plant_photos")
    suspend fun deleteAll()
}
