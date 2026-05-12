package com.yapt.planttracker.domain.model

data class CareLog(
    val id: Long = 0,
    val plantId: Long,
    val careType: CareType,
    val loggedAt: Long = System.currentTimeMillis(),
    val notes: String? = null,
    val photoUri: String? = null,
    val amount: String? = null,
    val wateringFeedback: WateringFeedback? = null
)
