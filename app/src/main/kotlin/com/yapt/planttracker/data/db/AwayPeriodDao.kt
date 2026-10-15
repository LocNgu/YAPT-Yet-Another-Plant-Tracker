package com.yapt.planttracker.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.yapt.planttracker.data.entity.AwayPeriodEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AwayPeriodDao {

    @Query("SELECT * FROM away_periods ORDER BY firstDay, id")
    fun observeAll(): Flow<List<AwayPeriodEntity>>

    @Query("SELECT * FROM away_periods ORDER BY firstDay, id")
    suspend fun getAll(): List<AwayPeriodEntity>

    @Insert
    suspend fun insert(period: AwayPeriodEntity): Long

    @Insert
    suspend fun insertAll(periods: List<AwayPeriodEntity>)

    @Update
    suspend fun update(period: AwayPeriodEntity)

    @Delete
    suspend fun delete(period: AwayPeriodEntity)

    @Query("DELETE FROM sitter_visits WHERE id IN (:visitIds)")
    suspend fun deleteVisits(visitIds: List<Long>)

    /** Updates [period] and deletes the visits its new dates left outside, in one transaction. */
    @Transaction
    suspend fun updateAndDeleteVisits(period: AwayPeriodEntity, visitIds: List<Long>) {
        update(period)
        if (visitIds.isNotEmpty()) deleteVisits(visitIds)
    }

    @Query("DELETE FROM away_periods")
    suspend fun deleteAll()
}
