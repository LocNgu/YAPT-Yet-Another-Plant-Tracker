package com.yapt.planttracker.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "care_logs",
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
data class CareLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val plantId: Long,
    val careType: String,
    val loggedAt: Long,
    val notes: String?,
    val photoUri: String?,
    val amount: String?,
    val wateringFeedback: String?,
    val fertilizerType: String = "UNSPECIFIED"
)
