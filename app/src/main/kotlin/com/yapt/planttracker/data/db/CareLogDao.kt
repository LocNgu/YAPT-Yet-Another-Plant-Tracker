package com.yapt.planttracker.data.db

import androidx.room.*
import com.yapt.planttracker.data.entity.CareLogEntity
import kotlinx.coroutines.flow.Flow

/** Projection of the most recent care-log timestamp for a plant within a queried window. */
data class PlantLastCare(val plantId: Long, val lastCareAt: Long)

@Dao
interface CareLogDao {

    @Query("SELECT * FROM care_logs WHERE plantId = :plantId ORDER BY loggedAt DESC")
    fun getLogsForPlant(plantId: Long): Flow<List<CareLogEntity>>

    @Query("SELECT * FROM care_logs")
    fun getAllLogs(): Flow<List<CareLogEntity>>

    @Query(
        "SELECT * FROM care_logs WHERE plantId = :plantId AND careType = :careType " +
            "ORDER BY loggedAt DESC LIMIT 1"
    )
    suspend fun getLastLogOfType(plantId: Long, careType: String): CareLogEntity?

    @Query(
        "SELECT * FROM care_logs WHERE plantId = :plantId AND careType = :careType " +
            "ORDER BY loggedAt DESC LIMIT 2"
    )
    suspend fun getLastTwoLogsOfType(plantId: Long, careType: String): List<CareLogEntity>

    /**
     * The single most recent [careType] log for [plantId] strictly before [beforeMillis] —
     * [beforeMillis] is chronological (the log's `loggedAt`), not insertion order, so a caller
     * backdating a log finds *that log's own* preceding neighbor rather than "whichever two rows
     * happen to be newest by `loggedAt` overall" (#654 round-2 review fix; see
     * [com.yapt.planttracker.domain.usecase.QuickLogUseCase.computeSuggestion]).
     */
    @Query(
        "SELECT * FROM care_logs WHERE plantId = :plantId AND careType = :careType " +
            "AND loggedAt < :beforeMillis ORDER BY loggedAt DESC LIMIT 1"
    )
    suspend fun getLastLogOfTypeBefore(plantId: Long, careType: String, beforeMillis: Long): CareLogEntity?

    /**
     * The most recent [limit] logs of [careType] for [plantId], newest first — used to derive
     * [com.yapt.planttracker.domain.schedule.CareSchedule.correctionStreak] over a bounded window
     * (#568) rather than caching a "last correction direction" that could go stale across an edit or
     * delete of a past log.
     */
    @Query(
        "SELECT * FROM care_logs WHERE plantId = :plantId AND careType = :careType " +
            "ORDER BY loggedAt DESC LIMIT :limit"
    )
    suspend fun getRecentLogsOfType(plantId: Long, careType: String, limit: Int): List<CareLogEntity>

    @Query("SELECT COUNT(*) FROM care_logs WHERE plantId = :plantId")
    suspend fun getCareLogCount(plantId: Long): Int

    /**
     * Every [careType] log timestamp for [plantId], oldest first — feeds
     * [com.yapt.planttracker.domain.schedule.CareSchedule.bootstrapBaseInterval] (#571), which needs
     * the full ordered WATER-log history (or a boundary-filtered slice of it) to compute gaps.
     */
    @Query(
        "SELECT loggedAt FROM care_logs WHERE plantId = :plantId AND careType = :careType " +
            "ORDER BY loggedAt ASC"
    )
    suspend fun getLogTimestampsOfTypeAscending(plantId: Long, careType: String): List<Long>

    /**
     * The most recent care-log timestamp per plant for logs in the half-open window
     * `[startMillis, endMillis)`. One row per plant that has ≥ 1 log in the window.
     */
    @Query(
        "SELECT plantId, MAX(loggedAt) AS lastCareAt FROM care_logs " +
            "WHERE loggedAt >= :startMillis AND loggedAt < :endMillis GROUP BY plantId"
    )
    suspend fun getLastCareBetween(startMillis: Long, endMillis: Long): List<PlantLastCare>

    @Query("SELECT * FROM care_logs WHERE photoUri IS NOT NULL AND plantId = :plantId ORDER BY loggedAt DESC")
    fun getPhotoLogsForPlant(plantId: Long): Flow<List<CareLogEntity>>

    /**
     * Count of [careType] logs for [plantId] with `loggedAt` in the half-open window
     * `[startMillis, endMillis)`, optionally excluding a specific log id (edit-mode duplicate
     * checks must not count the log being edited against itself). Used to guard against
     * same-day duplicate WATER/FERTILIZE logs (#509).
     */
    @Query(
        "SELECT COUNT(*) FROM care_logs WHERE plantId = :plantId AND careType = :careType " +
            "AND loggedAt >= :startMillis AND loggedAt < :endMillis " +
            "AND (:excludeId IS NULL OR id != :excludeId)"
    )
    suspend fun countLogsOfTypeOnDay(
        plantId: Long,
        careType: String,
        startMillis: Long,
        endMillis: Long,
        excludeId: Long?
    ): Int

    @Query("SELECT * FROM care_logs WHERE id = :id LIMIT 1")
    suspend fun getLogById(id: Long): CareLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: CareLogEntity): Long

    @Update
    suspend fun updateLog(log: CareLogEntity)

    @Delete
    suspend fun deleteLog(log: CareLogEntity)

    @Query("DELETE FROM care_logs")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<CareLogEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM care_logs")
    fun observeLogCount(): Flow<Int>
}
