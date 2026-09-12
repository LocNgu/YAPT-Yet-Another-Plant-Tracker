package com.yapt.planttracker.domain.notification

import androidx.annotation.StringRes
import com.yapt.planttracker.R
import com.yapt.planttracker.util.toLocalDate

data class PostWateringReminderContent(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int
)

/** Pure notification policy/composition for the post-watering reminder (#519). */
object PostWateringReminderNotificationComposer {

    fun shouldSchedule(loggedAt: Long, now: Long): Boolean =
        loggedAt.toLocalDate() == now.toLocalDate()

    fun compose(): PostWateringReminderContent = PostWateringReminderContent(
        titleRes = R.string.post_watering_notification_title,
        bodyRes = R.string.post_watering_notification_body
    )
}
