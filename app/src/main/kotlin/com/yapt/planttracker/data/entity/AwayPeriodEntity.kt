package com.yapt.planttracker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// firstDay/lastDay are inclusive local epoch days (LocalDate.toEpochDay()), not instants (technical ADR-0038).
// No foreign keys: a trip is global, not tied to a plant.
@Entity(tableName = "away_periods")
data class AwayPeriodEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val firstDay: Long,
    val lastDay: Long,
    val visitEveryDays: Int?,
    val createdAt: Long
)
