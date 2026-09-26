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

    /**
     * Every plant regardless of archive state, ordered like [getAllPlants] — the backup export
     * source of truth (#743), since archiving is meant to be soft/reversible, not a silent
     * exclusion from `.yapt` backups.
     */
    @Query("SELECT * FROM plants ORDER BY name ASC")
    fun getAllPlantsIncludingArchived(): Flow<List<PlantEntity>>

    @Query("SELECT COUNT(*) FROM plants WHERE archivedAt IS NOT NULL")
    fun getArchivedCount(): Flow<Int>

    @Query("UPDATE plants SET archivedAt = :timestamp WHERE id = :id")
    suspend fun archivePlant(id: Long, timestamp: Long)

    @Query("UPDATE plants SET archivedAt = NULL WHERE id = :id")
    suspend fun restorePlant(id: Long)

    /**
     * Column-specific update touching only `wateringBaseIntervalDays`/`updatedAt` (#703 review round
     * 3) — [SeasonalGraduationFixup][com.yapt.planttracker.domain.usecase.SeasonalGraduationFixup]
     * runs concurrently with UI edits on a background dispatcher; a full-row `@Update` built from a
     * plant object fetched moments earlier could race a concurrent edit to any *other* column and
     * silently revert it. This statement can't touch a column it doesn't name, eliminating that race
     * entirely rather than just narrowing its window.
     */
    @Query(
        "UPDATE plants SET wateringBaseIntervalDays = :wateringBaseIntervalDays, updatedAt = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateWateringBaseInterval(id: Long, wateringBaseIntervalDays: Double, updatedAt: Long)

    /**
     * Column-specific update touching only `wateringDueDateOverride`/`updatedAt`, same rationale as
     * [updateWateringBaseInterval] (#703 review round 3) — this statement can't touch a column it
     * doesn't name, eliminating the race entirely rather than just narrowing its window. Used by
     * `QuickLogUseCase.recordReschedule()` (#738, product ADR-0039) so a reschedule can never
     * silently revert a concurrent write to any other column.
     */
    @Query(
        "UPDATE plants SET wateringDueDateOverride = :wateringDueDateOverride, updatedAt = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateWateringDueDateOverride(id: Long, wateringDueDateOverride: Long?, updatedAt: Long)

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

    /**
     * Every non-null `coverPhotoUri`, **including archived plants** — feeds
     * [com.yapt.planttracker.util.OrphanPhotoSweeper]'s referenced-file set (#736). Deliberately not
     * filtered by `archivedAt`, unlike [getAllPlants] — an archived plant's cover photo is still
     * referenced and must never be swept.
     */
    @Query("SELECT coverPhotoUri FROM plants WHERE coverPhotoUri IS NOT NULL")
    suspend fun getAllCoverPhotoUris(): List<String>
}
