package com.yapt.planttracker.domain.model

/**
 * A user-defined, free-text recurring reminder for a plant (issue #232) — the delivery mechanism
 * for disease/treatment schedules ("apply neem oil every 7 days") as well as anything else not
 * worth a dedicated built-in [CareType]. Recurs indefinitely until the user deletes it; there is no
 * end date or occurrence count. Completing one writes a [CareLog] with [CareType.CUSTOM] and this
 * reminder's id (see [CareLog.customReminderId]), then resets [lastDoneAt].
 */
data class CustomReminder(
    val id: Long = 0,
    val plantId: Long,
    val name: String,
    val intervalDays: Int,
    val lastDoneAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Due-status projection for one [CustomReminder], mirroring [PlantCareStatus]'s per-type fields but
 * shaped as a list since a plant can have an unbounded number of custom reminders (unlike the
 * single nullable-column extended-care types such as repotting).
 */
data class CustomReminderStatus(
    val reminder: CustomReminder,
    val nextDueAt: Long?,
    val isOverdue: Boolean,
    val isDueSoon: Boolean
)
