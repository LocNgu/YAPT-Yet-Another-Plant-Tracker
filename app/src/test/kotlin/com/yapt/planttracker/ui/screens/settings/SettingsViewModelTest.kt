package com.yapt.planttracker.ui.screens.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

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
    }

    @Test
    fun `notificationsEnabled defaults to true when key absent`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = SettingsViewModel(mockDataStore, mockContext, mockDatabase)

        vm.notificationsEnabled.test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reminderHour defaults to 9 when key absent`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = SettingsViewModel(mockDataStore, mockContext, mockDatabase)

        vm.reminderHour.test {
            assertEquals(9, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reminderMinute defaults to 0 when key absent`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = SettingsViewModel(mockDataStore, mockContext, mockDatabase)

        vm.reminderMinute.test {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `notificationsEnabled reflects stored value`() = runTest {
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
    fun `reminderHour reflects stored value`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns 20
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = SettingsViewModel(mockDataStore, mockContext, mockDatabase)

        vm.reminderHour.test {
            assertEquals(20, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
