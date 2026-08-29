package com.yapt.planttracker.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watering_adjustments",
    foreignKeys = [
        ForeignKey(
            entity = PlantEntity::class,
            parentColumns = ["id"],
            childColumns = ["plantId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["plantId"])]
)
data class WateringAdjustmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val plantId: Long,
    val triggeredAt: Long,
    // WateringAdjustmentTrigger.name, runCatching-decoded per convention (never a plain .valueOf()).
    val trigger: String,
    val beforeIntervalDays: Int,
    val afterIntervalDays: Int
)
