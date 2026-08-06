package com.yapt.planttracker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "plants")
data class PlantEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val species: String?,
    val room: String?,
    val coverPhotoUri: String?,
    val notes: String?,
    val wateringIntervalDays: Int?,
    val fertilizingIntervalDays: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val wateringDueDateOverride: Long? = null,
    val useLiquidFertilizer: Boolean = false,
    val archivedAt: Long? = null,
    val repottingIntervalDays: Int? = null
)
