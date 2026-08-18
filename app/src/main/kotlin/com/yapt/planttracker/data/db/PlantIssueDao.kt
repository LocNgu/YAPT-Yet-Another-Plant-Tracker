package com.yapt.planttracker.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yapt.planttracker.data.entity.PlantIssueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlantIssueDao {

    @Query("SELECT * FROM plant_issues ORDER BY startedAt ASC")
    fun getAllIssues(): Flow<List<PlantIssueEntity>>

    @Query("SELECT * FROM plant_issues WHERE plantId = :plantId ORDER BY startedAt ASC")
    fun getIssuesForPlant(plantId: Long): Flow<List<PlantIssueEntity>>

    @Query(
        "SELECT * FROM plant_issues WHERE plantId = :plantId AND resolvedAt IS NULL ORDER BY startedAt ASC"
    )
    fun getActiveIssuesForPlant(plantId: Long): Flow<List<PlantIssueEntity>>

    @Query("SELECT COUNT(*) FROM plant_issues WHERE plantId = :plantId AND resolvedAt IS NULL")
    suspend fun getActiveIssueCountForPlant(plantId: Long): Int

    @Query("SELECT * FROM plant_issues WHERE id = :id LIMIT 1")
    suspend fun getIssueById(id: Long): PlantIssueEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIssue(issue: PlantIssueEntity): Long

    @Update
    suspend fun updateIssue(issue: PlantIssueEntity)

    @Delete
    suspend fun deleteIssue(issue: PlantIssueEntity)

    @Query("DELETE FROM plant_issues")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(issues: List<PlantIssueEntity>): List<Long>
}
