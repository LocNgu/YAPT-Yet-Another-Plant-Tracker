package com.yapt.planttracker.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "plant_issues",
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
data class PlantIssueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val plantId: Long,
    val name: String,
    val startedAt: Long,
    val resolvedAt: Long? = null,
    val resolutionNote: String? = null,
    // No FK constraint: same deliberately-unenforced-link pattern as CareLog.customReminderId
    // (technical ADR-0019) — resolving/deleting a PlantIssue never touches the linked reminder,
    // and the reminder may since have been deleted.
    val linkedReminderId: Long? = null
)
