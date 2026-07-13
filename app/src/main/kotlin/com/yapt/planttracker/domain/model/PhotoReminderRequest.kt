package com.yapt.planttracker.domain.model

/** Carries a photo-reminder prompt from a quick action back to the calling screen. */
data class PhotoReminderRequest(val plantId: Long, val plantName: String, val daysSince: Long)
