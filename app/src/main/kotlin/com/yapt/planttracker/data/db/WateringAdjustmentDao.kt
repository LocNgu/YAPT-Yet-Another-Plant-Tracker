package com.yapt.planttracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yapt.planttracker.data.entity.WateringAdjustmentEntity
import com.yapt.planttracker.util.toLocalDate
import kotlinx.coroutines.flow.Flow

@Dao
abstract class WateringAdjustmentDao {

    @Query("SELECT * FROM watering_adjustments ORDER BY triggeredAt DESC")
    abstract fun getAllAdjustments(): Flow<List<WateringAdjustmentEntity>>

    // id DESC is a secondary sort key, not just tie-break cosmetics: #761 (product ADR-0044) is the
    // first place two rows (DORMANCY_EXCLUDED + DORMANCY_EXIT) are written with an identical
    // triggeredAt for one logical observation, and SQLite's insertion-order-on-a-tie is not a
    // contract — id DESC deterministically puts the later-inserted row (the higher autoincrement id)
    // first, matching "Recent adjustments"'s own most-recent-first framing (Codex review round 1 on
    // #776, item 5).
    @Query(
        "SELECT * FROM watering_adjustments WHERE plantId = :plantId ORDER BY triggeredAt DESC, id DESC LIMIT :limit"
    )
    abstract fun getRecentForPlant(plantId: Long, limit: Int): Flow<List<WateringAdjustmentEntity>>

    // #699/#761 (product ADR-0044 — Codex review round 2 on #776, P1-2): lets a write path check
    // whether a given trigger has already been recorded for a plant before writing another one — the
    // dormancy-exit idempotency fix needs to see every existing DORMANCY_EXIT row, not just the most
    // recent [limit] the sheet shows, so this is a plain unbounded one-shot query, not a Flow.
    @Query("SELECT * FROM watering_adjustments WHERE plantId = :plantId AND trigger = :trigger")
    abstract suspend fun getAdjustmentsByTrigger(plantId: Long, trigger: String): List<WateringAdjustmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAdjustment(adjustment: WateringAdjustmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(adjustments: List<WateringAdjustmentEntity>): List<Long>

    @Query("DELETE FROM watering_adjustments")
    abstract suspend fun deleteAll()

    @Query("SELECT * FROM watering_adjustments WHERE plantId = :plantId ORDER BY triggeredAt DESC, id DESC LIMIT 1")
    abstract suspend fun getNewestForPlant(plantId: Long): WateringAdjustmentEntity?

    @Query("UPDATE watering_adjustments SET afterIntervalDays = :after, triggeredAt = :triggeredAt WHERE id = :id")
    abstract suspend fun updateAfterAndTime(id: Long, after: Int, triggeredAt: Long)

    /**
     * #799 (product ADR-0082): write a `MANUAL_EDIT` row, merging into the plant's newest row when that row is
     * itself a `MANUAL_EDIT` from the same local day (keeps its `beforeIntervalDays`, takes the new
     * `afterIntervalDays`/`triggeredAt`). Read-then-write in one transaction so two quick writes can't both insert.
     */
    @Transaction
    open suspend fun recordManualEdit(adjustment: WateringAdjustmentEntity): Long {
        val newest = getNewestForPlant(adjustment.plantId)
        val mergeable = newest != null &&
            newest.trigger == MANUAL_EDIT_TRIGGER &&
            newest.triggeredAt.toLocalDate() == adjustment.triggeredAt.toLocalDate()
        if (newest != null && mergeable) {
            updateAfterAndTime(newest.id, adjustment.afterIntervalDays, adjustment.triggeredAt)
            return newest.id
        }
        return insertAdjustment(adjustment)
    }

    private companion object {
        const val MANUAL_EDIT_TRIGGER = "MANUAL_EDIT"
    }
}
