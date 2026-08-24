package com.yapt.planttracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yapt.planttracker.data.entity.WateringAdjustmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WateringAdjustmentDao {

    @Query("SELECT * FROM watering_adjustments ORDER BY triggeredAt DESC")
    fun getAllAdjustments(): Flow<List<WateringAdjustmentEntity>>

    @Query("SELECT * FROM watering_adjustments WHERE plantId = :plantId ORDER BY triggeredAt DESC LIMIT :limit")
    fun getRecentForPlant(plantId: Long, limit: Int): Flow<List<WateringAdjustmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAdjustment(adjustment: WateringAdjustmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(adjustments: List<WateringAdjustmentEntity>): List<Long>

    @Query("DELETE FROM watering_adjustments")
    suspend fun deleteAll()
}
