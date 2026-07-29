package com.yapt.planttracker.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    @Test
    fun `createChannel registers the plant care reminder channel`() {
        NotificationHelper.createChannel(context)

        val channel = notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_ID)
        assertNotNull("Expected the plant care channel to be registered", channel)
        assertEquals(NotificationHelper.CHANNEL_NAME, channel.name.toString())
        assertEquals(NotificationHelper.CHANNEL_DESCRIPTION, channel.description)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun `createChannel is idempotent - repeated calls keep a single channel`() {
        NotificationHelper.createChannel(context)
        NotificationHelper.createChannel(context)

        val matching = notificationManager.notificationChannels.count {
            it.id == NotificationHelper.CHANNEL_ID
        }
        assertEquals(1, matching)
    }
}
