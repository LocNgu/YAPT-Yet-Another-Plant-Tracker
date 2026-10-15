package com.yapt.planttracker.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sitter_visits",
    foreignKeys = [
        ForeignKey(
            entity = AwayPeriodEntity::class,
            parentColumns = ["id"],
            childColumns = ["awayPeriodId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["awayPeriodId"])]
)
data class SitterVisitEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val awayPeriodId: Long,
    val day: Long,
    val state: String,
    val createdAt: Long
)
