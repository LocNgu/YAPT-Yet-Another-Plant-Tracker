package com.yapt.planttracker.domain.model

data class CareLog(
    val id: Long = 0,
    val plantId: Long,
    val careType: CareType,
    val loggedAt: Long = System.currentTimeMillis(),
    val notes: String? = null,
    val photoUri: String? = null,
    val amount: String? = null,
    val wateringFeedback: WateringFeedback? = null,
    val fertilizerType: FertilizerType = FertilizerType.UNSPECIFIED,
    /**
     * Traces a [CareType.CUSTOM] log back to the specific [CustomReminder] it satisfied — a plant
     * can have multiple custom reminders, so the bare [careType] alone can't identify which one. May
     * point to a since-deleted reminder (no FK enforced); display code must fall back to the generic
     * [CareType.CUSTOM] label rather than crash (#232).
     */
    val customReminderId: Long? = null
)
