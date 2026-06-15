package com.yapt.planttracker.domain.model

data class Plant(
    val id: Long = 0,
    val name: String,
    val species: String? = null,
    val room: String? = null,
    val coverPhotoUri: String? = null,
    val notes: String? = null,
    val wateringIntervalDays: Int? = null,
    val fertilizingIntervalDays: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val wateringDueDateOverride: Long? = null,
    val useLiquidFertilizer: Boolean = false,
    val archivedAt: Long? = null
)
