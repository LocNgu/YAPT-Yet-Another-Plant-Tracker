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

    // id DESC is a secondary sort key, not just tie-break cosmetics: #761 (product ADR-0044) is the
    // first place two rows (DORMANCY_EXCLUDED + DORMANCY_EXIT) are written with an identical
    // triggeredAt for one logical observation, and SQLite's insertion-order-on-a-tie is not a
    // contract — id DESC deterministically puts the later-inserted row (the higher autoincrement id)
    // first, matching "Recent adjustments"'s own most-recent-first framing (Codex review round 1 on
    // #776, item 5).
    @Query(
        "SELECT * FROM watering_adjustments WHERE plantId = :plantId ORDER BY triggeredAt DESC, id DESC LIMIT :limit"
    )
    fun getRecentForPlant(plantId: Long, limit: Int): Flow<List<WateringAdjustmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAdjustment(adjustment: WateringAdjustmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(adjustments: List<WateringAdjustmentEntity>): List<Long>

    @Query("DELETE FROM watering_adjustments")
    suspend fun deleteAll()
}
