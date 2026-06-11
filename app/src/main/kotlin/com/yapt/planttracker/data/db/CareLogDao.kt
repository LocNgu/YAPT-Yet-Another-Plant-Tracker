package com.yapt.planttracker.data.db

import androidx.room.*
import com.yapt.planttracker.data.entity.CareLogEntity
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT COUNT(*) FROM care_logs WHERE plantId = :plantId")
    suspend fun getCareLogCount(plantId: Long): Int

    @Query("SELECT * FROM care_logs WHERE photoUri IS NOT NULL AND plantId = :plantId ORDER BY loggedAt DESC")
    fun getPhotoLogsForPlant(plantId: Long): Flow<List<CareLogEntity>>

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
