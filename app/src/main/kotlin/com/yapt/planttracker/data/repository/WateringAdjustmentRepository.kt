package com.yapt.planttracker.data.repository

import com.yapt.planttracker.data.db.WateringAdjustmentDao
import com.yapt.planttracker.data.entity.WateringAdjustmentEntity
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WateringAdjustmentRepository(private val wateringAdjustmentDao: WateringAdjustmentDao) {

    /** Most recent [limit] adjustments for [plantId], newest first — feeds the "Why this date?" sheet (#572). */
    fun getRecentForPlant(plantId: Long, limit: Int): Flow<List<WateringAdjustment>> =
        wateringAdjustmentDao.getRecentForPlant(plantId, limit).map { list -> list.map { it.toDomain() } }

    suspend fun addAdjustment(adjustment: WateringAdjustment): Long =
        wateringAdjustmentDao.insertAdjustment(adjustment.toEntity())
}

private fun WateringAdjustmentEntity.toDomain() = WateringAdjustment(
    id = id,
    plantId = plantId,
    triggeredAt = triggeredAt,
    trigger = runCatching {
        WateringAdjustmentTrigger.valueOf(trigger)
    }.getOrDefault(WateringAdjustmentTrigger.WATER_NEUTRAL),
    beforeIntervalDays = beforeIntervalDays,
    afterIntervalDays = afterIntervalDays
)

private fun WateringAdjustment.toEntity() = WateringAdjustmentEntity(
    id = id,
    plantId = plantId,
    triggeredAt = triggeredAt,
    trigger = trigger.name,
    beforeIntervalDays = beforeIntervalDays,
    afterIntervalDays = afterIntervalDays
)
