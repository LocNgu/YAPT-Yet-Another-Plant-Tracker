package com.yapt.planttracker.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Away mode (#895, technical ADR-0038). Trips are global (no plant FK) and stored as local
// calendar days (LocalDate.toEpochDay()), not instants. Past trips are kept so later gap checks can
// still see them.
@Entity(tableName = "away_periods")
data class AwayPeriodEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val firstDay: Long,
    val lastDay: Long,
    val visitEveryDays: Int? = null,
    val createdAt: Long
)

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
    // SitterVisitState.name, runCatching-decoded per convention.
    val state: String,
    val createdAt: Long
)

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
    // null until the visit is confirmed.
    val watered: Boolean? = null
)
