package com.yapt.planttracker.notification

import android.app.NotificationManager
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.settingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PostWateringReminderPresentationTest {

    private lateinit var app: YaptApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking {
            app.settingsDataStore.edit { it.remove(SettingsKeys.POST_WATERING_REMINDER_PENDING_AT) }
        }
    }

    @After
    fun tearDown() {
        app.getSystemService(NotificationManager::class.java).cancelAll()
        runBlocking {
            app.settingsDataStore.edit { it.remove(SettingsKeys.POST_WATERING_REMINDER_PENDING_AT) }
        }
    }

    @Test
    fun `showInApp persists the due prompt token`() = runBlocking {
        PostWateringReminderPresentation.showInApp(app, app.settingsDataStore, triggeredAt = 123L)

        assertEquals(123L, PostWateringReminderPresentation.pendingPrompt(app.settingsDataStore).first())
    }

    @Test
    fun `dismiss clears only the prompt token that was displayed`() = runBlocking {
        PostWateringReminderPresentation.showInApp(app, app.settingsDataStore, triggeredAt = 456L)

        PostWateringReminderPresentation.dismiss(app, app.settingsDataStore, expectedTriggeredAt = 123L)
        assertEquals(456L, PostWateringReminderPresentation.pendingPrompt(app.settingsDataStore).first())

        PostWateringReminderPresentation.dismiss(app, app.settingsDataStore, expectedTriggeredAt = 456L)
        assertNull(PostWateringReminderPresentation.pendingPrompt(app.settingsDataStore).first())
    }
}
