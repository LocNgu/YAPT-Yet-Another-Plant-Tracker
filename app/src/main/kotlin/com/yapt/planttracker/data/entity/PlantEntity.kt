package com.yapt.planttracker.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "plants",
    foreignKeys = [
        ForeignKey(
            entity = PlantEntity::class,
            parentColumns = ["id"],
            childColumns = ["propagatedFromId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("propagatedFromId")]
)
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
    val repottingIntervalDays: Int? = null,
    val wateringConfidence: Int? = null,
    val wateringBaseIntervalDays: Double? = null,
    val pinIntervalToBase: Boolean = false,
    val wateringResetAt: Long? = null,
    val wateringFreezeUntil: Long? = null,
    val dormancyStartMonth: Int? = null,
    val dormancyEndMonth: Int? = null,
    /** Comma-separated [com.yapt.planttracker.domain.schedule.FertilizingSeason] names; `null` = every season (#795). */
    val fertilizingSeasons: String? = null,
    val dormantWateringIntervalDays: Int? = null,
    /** Start of day (system zone) of the planned repot's target season's first day; `null` = no plan (#809). */
    val repotPlanSeasonStartAt: Long? = null,
    /** Instant the plan was made — `updatedAt` can't stand in, it changes on every edit (#809). */
    val repotPlanMadeAt: Long? = null,
    /** Comma-separated [com.yapt.planttracker.domain.schedule.FertilizingSeason] names; `null` = every season. */
    val repottingSeasons: String? = null,
    /** Instant the plant was hidden from every operational surface; `null` = visible. Independent of [archivedAt] (#915). */
    val hiddenAt: Long? = null,
    /** The plant this one was propagated from (#960, product ADR-0072); self-FK, `ON DELETE SET NULL`. */
    val propagatedFromId: Long? = null
)
