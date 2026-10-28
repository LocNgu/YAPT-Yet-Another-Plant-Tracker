package com.yapt.planttracker.data.db

import androidx.room.*
import com.yapt.planttracker.data.entity.PlantEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlantDao {

    @Query(
        "SELECT * FROM plants WHERE archivedAt IS NULL AND hiddenAt IS NULL ORDER BY name ASC"
    )
    fun getAllPlants(): Flow<List<PlantEntity>>

    @Query("SELECT * FROM plants WHERE id = :plantId")
    fun getPlantById(plantId: Long): Flow<PlantEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlant(plant: PlantEntity): Long

    @Update
    suspend fun updatePlant(plant: PlantEntity)

    @Delete
    suspend fun deletePlant(plant: PlantEntity)

    @Query(
        "SELECT DISTINCT room FROM plants " +
            "WHERE room IS NOT NULL AND archivedAt IS NULL AND hiddenAt IS NULL ORDER BY room ASC"
    )
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

    /** Living plants the owner hid (#915), most recently hidden first. Archived plants are never listed here. */
    @Query("SELECT * FROM plants WHERE archivedAt IS NULL AND hiddenAt IS NOT NULL ORDER BY hiddenAt DESC")
    fun getHiddenPlants(): Flow<List<PlantEntity>>

    @Query("SELECT COUNT(*) FROM plants WHERE archivedAt IS NULL AND hiddenAt IS NOT NULL")
    fun getHiddenCount(): Flow<Int>

    /**
     * Every non-archived plant, visible and hidden alike (#915) — for background passes that must still
     * reach hidden plants, such as the seasonal graduation fixup, which [getAllPlants] would skip.
     */
    @Query("SELECT * FROM plants WHERE archivedAt IS NULL ORDER BY name ASC")
    fun getAllNonArchivedPlants(): Flow<List<PlantEntity>>

    /**
     * Column-specific hide/unhide (#915; the column-UPDATE style of technical ADR-0036 point 3): touches
     * only `hiddenAt`, so a concurrent edit to any other column can't be reverted. Deliberately leaves
     * `updatedAt` alone — hiding is a view preference, not an edit of the plant's care data.
     */
    @Query("UPDATE plants SET hiddenAt = :hiddenAt WHERE id = :id")
    suspend fun updateHiddenAt(id: Long, hiddenAt: Long?)

    @Query("UPDATE plants SET archivedAt = :timestamp, hiddenAt = NULL WHERE id = :id")
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

    /**
     * Column-specific update touching only `coverPhotoUri`/`updatedAt` (#808), same rationale as
     * [updateWateringDueDateOverride]: a cover change from Plant Detail's photo actions or the shared
     * photo-reminder save can't revert a concurrent write to any other column, because the statement
     * never names one. Like `updatePlant()` it deliberately does not fire the repository's
     * `onPhotoReferencesRemoved` hook — the daily sweep reclaims a replaced cover (technical ADR-0031).
     */
    @Query("UPDATE plants SET coverPhotoUri = :coverPhotoUri, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCoverPhotoUri(id: Long, coverPhotoUri: String?, updatedAt: Long)

    /**
     * Column-specific update touching only `repotPlanSeasonStartAt`/`repotPlanMadeAt`/`updatedAt` (#809,
     * product ADR-0057), same rationale as [updateWateringDueDateOverride]: a REPOT log clears a plan
     * from `QuickLogUseCase`/`AddCareLogViewModel` while other writers may be mid-flight, and a
     * statement that can't touch a column it doesn't name can't revert one. Both plan columns move
     * together — `null, null` clears the plan; a set-plan write always supplies both.
     */
    @Query(
        "UPDATE plants SET repotPlanSeasonStartAt = :repotPlanSeasonStartAt, " +
            "repotPlanMadeAt = :repotPlanMadeAt, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateRepotPlan(
        id: Long,
        repotPlanSeasonStartAt: Long?,
        repotPlanMadeAt: Long?,
        updatedAt: Long
    )

    /**
     * Column-specific update touching only `wateringConfidence`/`updatedAt` (#960, product ADR-0072;
     * technical ADR-0036 point 3) — the PROPAGATE confidence drop re-reads the plant fresh and then writes
     * just this column, so it can't revert a concurrent edit to any other one.
     */
    @Query("UPDATE plants SET wateringConfidence = :confidence, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateWateringConfidence(id: Long, confidence: Int?, updatedAt: Long)

    /**
     * Unconditional parent-link write (#960): unlink (`null`) and backup restore, which validates its own
     * links. Touches only `propagatedFromId` and leaves `updatedAt` alone — a link is not an edit of the
     * plant's care data. Anything that can introduce a cycle goes through [linkToParent] instead.
     */
    @Query("UPDATE plants SET propagatedFromId = :parentId WHERE id = :id")
    suspend fun setPropagatedFrom(id: Long, parentId: Long?)

    /**
     * Sets [id]'s parent to [parentId] unless that would link a plant to itself, to a missing plant, or to
     * one of its own descendants (#960, product ADR-0072). One statement, so the cycle check and the write
     * are atomic. Returns the number of rows changed: 0 means the link was refused.
     */
    @Query(
        "UPDATE plants SET propagatedFromId = :parentId " +
            "WHERE id = :id AND id != :parentId " +
            "AND EXISTS (SELECT 1 FROM plants WHERE id = :parentId) " +
            "AND :parentId NOT IN (" +
            "WITH RECURSIVE descendants(id) AS (" +
            "SELECT id FROM plants WHERE propagatedFromId = :id " +
            "UNION SELECT p.id FROM plants p JOIN descendants d ON p.propagatedFromId = d.id) " +
            "SELECT id FROM descendants)"
    )
    suspend fun linkToParent(id: Long, parentId: Long): Int

    /** Direct children of [parentId], archived and hidden ones included (#960), by name. */
    @Query("SELECT * FROM plants WHERE propagatedFromId = :parentId ORDER BY name ASC, id ASC")
    fun getChildren(parentId: Long): Flow<List<PlantEntity>>

    /** Every transitive child of [id] (#960); the cycle guard and the picker's "Link parent…" exclusion. */
    @Query(
        "WITH RECURSIVE descendants(id) AS (" +
            "SELECT id FROM plants WHERE propagatedFromId = :id " +
            "UNION SELECT p.id FROM plants p JOIN descendants d ON p.propagatedFromId = d.id) " +
            "SELECT id FROM descendants"
    )
    suspend fun getDescendantIds(id: Long): List<Long>

    /** Every transitive parent of [id] (#960); the picker's "Link existing" exclusion. */
    @Query(
        "WITH RECURSIVE ancestors(id) AS (" +
            "SELECT propagatedFromId FROM plants WHERE id = :id AND propagatedFromId IS NOT NULL " +
            "UNION SELECT p.propagatedFromId FROM plants p JOIN ancestors a ON p.id = a.id " +
            "WHERE p.propagatedFromId IS NOT NULL) " +
            "SELECT id FROM ancestors"
    )
    suspend fun getAncestorIds(id: Long): List<Long>

    // Single-statement batch variants so bulk archive/restore apply atomically — a killed
    // process can't leave some of the selected plants archived and others not (#448).
    @Query("UPDATE plants SET archivedAt = :timestamp, hiddenAt = NULL WHERE id IN (:ids)")
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
