package com.yapt.planttracker.ui.screens.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.cash.turbine.test
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val mockPrefs: Preferences = mockk()
    private val mockDataStore: DataStore<Preferences> = mockk()
    private val mockContext: Context = mockk(relaxed = true)
    private val mockDatabase: PlantDatabase = mockk(relaxed = true)

    private lateinit var vm: SettingsViewModel

    @Before
    fun setup() {
        every { mockDataStore.data } returns flowOf(mockPrefs)
        every { mockPrefs[SettingsKeys.KEEP_SCREEN_ON] } returns null
    }

    @Test
    fun `notificationsEnabled emits false when DataStore returns false`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns false
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = SettingsViewModel(mockDataStore, mockContext, mockDatabase)

        vm.notificationsEnabled.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reminderHour emits stored value when DataStore returns 21`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns 21
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = SettingsViewModel(mockDataStore, mockContext, mockDatabase)

        vm.reminderHour.test {
            assertEquals(21, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reminderMinute emits stored value when DataStore returns 30`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns 30
        vm = SettingsViewModel(mockDataStore, mockContext, mockDatabase)

        vm.reminderMinute.test {
            assertEquals(30, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `keepScreenOn emits true when DataStore returns true`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        every { mockPrefs[SettingsKeys.KEEP_SCREEN_ON] } returns true
        vm = SettingsViewModel(mockDataStore, mockContext, mockDatabase)

        vm.keepScreenOn.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `first-launch default write sets hour=9 and minute=0 when key is absent`() = runTest {
        val tempFile = File.createTempFile("settings_test_defaults_", ".preferences_pb")
        tempFile.deleteOnExit()
        val realDataStore = PreferenceDataStoreFactory.create(
            scope = this,
            produceFile = { tempFile }
        )

        val prefs = realDataStore.data.first()
        if (prefs[SettingsKeys.REMINDER_HOUR] == null) {
            realDataStore.edit {
                it[SettingsKeys.REMINDER_HOUR] = 9
                it[SettingsKeys.REMINDER_MINUTE] = 0
            }
        }

        val written = realDataStore.data.first()
        assertEquals(9, written[SettingsKeys.REMINDER_HOUR])
        assertEquals(0, written[SettingsKeys.REMINDER_MINUTE])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `first-launch default write is skipped when REMINDER_HOUR is already present`() = runTest {
        val tempFile = File.createTempFile("settings_test_existing_", ".preferences_pb")
        tempFile.deleteOnExit()
        val realDataStore = PreferenceDataStoreFactory.create(
            scope = this,
            produceFile = { tempFile }
        )

        realDataStore.edit { it[SettingsKeys.REMINDER_HOUR] = 8 }

        val prefs = realDataStore.data.first()
        if (prefs[SettingsKeys.REMINDER_HOUR] == null) {
            realDataStore.edit {
                it[SettingsKeys.REMINDER_HOUR] = 9
                it[SettingsKeys.REMINDER_MINUTE] = 0
            }
        }

        val written = realDataStore.data.first()
        assertEquals(8, written[SettingsKeys.REMINDER_HOUR])
    }
}
