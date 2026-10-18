package com.yapt.planttracker.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// watered is null until the visit is confirmed.
@Entity(
    tableName = "sitter_visit_plants",
    primaryKeys = ["visitId", "plantId"],
    foreignKeys = [
        ForeignKey(
            entity = SitterVisitEntity::class,
            parentColumns = ["id"],
            childColumns = ["visitId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PlantEntity::class,
            parentColumns = ["id"],
            childColumns = ["plantId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["plantId"])]
)
data class SitterVisitPlantEntity(
    val visitId: Long,
    val plantId: Long,
    val watered: Boolean?
)
