package com.yapt.planttracker.domain.model

data class PlantCareStatus(
    val plant: Plant,
    val lastWateredAt: Long?,
    val lastFertilizedAt: Long?,
    val daysSinceLastWatering: Long?,
    val nextWateringDueAt: Long?,
    val isOverdue: Boolean,
    val isDueSoon: Boolean,
    val nextFertilizingDueAt: Long?,
    val isFertilizingOverdue: Boolean,
    val isFertilizingDueSoon: Boolean,
    val totalCareLogs: Int,
    val lastRepottedAt: Long? = null,
    val nextRepottingDueAt: Long? = null,
    val isRepottingOverdue: Boolean = false,
    val isRepottingDueSoon: Boolean = false,
    val customReminderStatuses: List<CustomReminderStatus> = emptyList()
)
