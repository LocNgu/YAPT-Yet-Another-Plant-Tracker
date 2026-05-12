package com.yapt.planttracker.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationHelper {

    const val CHANNEL_ID = "plant_care_reminders"
    const val CHANNEL_NAME = "Plant Care Reminders"
    const val CHANNEL_DESCRIPTION = "Reminders to water and care for your plants"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESCRIPTION
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
