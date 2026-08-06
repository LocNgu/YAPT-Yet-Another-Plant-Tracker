package com.yapt.planttracker.data.db

import androidx.room.*
import com.yapt.planttracker.data.entity.PlantEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlantDao {

    @Query("SELECT * FROM plants WHERE archivedAt IS NULL ORDER BY name ASC")
    fun getAllPlants(): Flow<List<PlantEntity>>

    @Query("SELECT * FROM plants WHERE id = :plantId")
    fun getPlantById(plantId: Long): Flow<PlantEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlant(plant: PlantEntity): Long

    @Update
    suspend fun updatePlant(plant: PlantEntity)

    @Delete
    suspend fun deletePlant(plant: PlantEntity)

    @Query("SELECT DISTINCT room FROM plants WHERE room IS NOT NULL AND archivedAt IS NULL ORDER BY room ASC")
    fun getAllRooms(): Flow<List<String>>

    @Query("DELETE FROM plants")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(plants: List<PlantEntity>): List<Long>

    @Query("SELECT * FROM plants WHERE archivedAt IS NOT NULL ORDER BY archivedAt DESC")
    fun getArchivedPlants(): Flow<List<PlantEntity>>

    @Query("SELECT COUNT(*) FROM plants WHERE archivedAt IS NOT NULL")
    fun getArchivedCount(): Flow<Int>

    @Query("UPDATE plants SET archivedAt = :timestamp WHERE id = :id")
    suspend fun archivePlant(id: Long, timestamp: Long)

    @Query("UPDATE plants SET archivedAt = NULL WHERE id = :id")
    suspend fun restorePlant(id: Long)

    // Single-statement batch variants so bulk archive/restore apply atomically — a killed
    // process can't leave some of the selected plants archived and others not (#448).
    @Query("UPDATE plants SET archivedAt = :timestamp WHERE id IN (:ids)")
    suspend fun archivePlants(ids: List<Long>, timestamp: Long)

    @Query("UPDATE plants SET archivedAt = NULL WHERE id IN (:ids)")
    suspend fun restorePlants(ids: List<Long>)

    @Query("DELETE FROM plants WHERE archivedAt IS NOT NULL")
    suspend fun deleteAllArchived()

    /**
     * Hard-deletes every plant whose name starts with [prefix], **regardless of `archivedAt`**
     * (active and archived alike) — used to remove developer-mode demo plants (#523). Cascades to
     * `care_logs` and `plant_photos` via their `ON DELETE CASCADE` foreign keys. Returns the
     * number of plant rows deleted.
     */
    @Query("DELETE FROM plants WHERE name LIKE :prefix || '%'")
    suspend fun deletePlantsByNamePrefix(prefix: String): Int
}
