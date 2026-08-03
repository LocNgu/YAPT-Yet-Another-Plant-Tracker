package com.yapt.planttracker.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.PlantRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: PlantDatabase
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var viewModel: SettingsViewModel
    private lateinit var dataStoreFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        dataStoreScope = CoroutineScope(Dispatchers.IO)
        dataStoreFile = File(context.cacheDir, "settings_test_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { dataStoreFile }
        )

        val plantRepository = PlantRepository(database.plantDao())
        viewModel = SettingsViewModel(dataStore, context, database, plantRepository)
    }

    @After
    fun tearDown() {
        database.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
    }

    @Test
    fun exportBackupButton_isDisplayed() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        composeTestRule.onNodeWithText("Export backup").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun restoreFromBackupButton_isDisplayed() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        composeTestRule.onNodeWithText("Restore from backup").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun whatsNewRow_isDisplayed() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        composeTestRule.onNodeWithText("What's New").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun whatsNewRow_invokesCallback() {
        var called = false
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = { called = true }
            )
        }

        composeTestRule.onNodeWithText("What's New").performScrollTo().performClick()
        assert(called)
    }

    @Test
    fun combineNotificationsRow_isDisplayed_whenNotificationsEnabled() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        composeTestRule.onNodeWithText("Combine reminders").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun combineNotificationsRow_isHidden_whenNotificationsDisabled() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        composeTestRule.onNodeWithTag("notifications_enabled_switch").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Combine reminders").assertDoesNotExist()
    }

    @Test
    fun combineNotificationsSwitch_startsOff_andTogglingInvokesViewModelSetter() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        composeTestRule.onNodeWithTag("combine_notifications_switch").performScrollTo().assertIsOff()

        composeTestRule.onNodeWithTag("combine_notifications_switch").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("combine_notifications_switch").assertIsOn()
    }

    @Test
    fun fertilizingNotificationsRow_isDisplayed_whenNotificationsEnabled() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        composeTestRule.onNodeWithText("Notify for fertilizing").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fertilizingNotificationsRow_isHidden_whenNotificationsDisabled() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        composeTestRule.onNodeWithTag("notifications_enabled_switch").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Notify for fertilizing").assertDoesNotExist()
    }

    @Test
    fun fertilizingNotificationsSwitch_startsOn_andTogglingInvokesViewModelSetter() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        // Defaults to on (existing behaviour preserved for existing users).
        composeTestRule.onNodeWithTag("fertilizing_notifications_switch").performScrollTo().assertIsOn()

        composeTestRule.onNodeWithTag("fertilizing_notifications_switch").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("fertilizing_notifications_switch").assertIsOff()
    }

    @Test
    fun graveyardRow_isDisplayed() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        composeTestRule.onNodeWithText("Plant Graveyard").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun graveyardRow_onClick_invokesCallback() {
        var called = false
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {},
                onNavigateToGraveyard = { called = true }
            )
        }

        composeTestRule.onNodeWithText("Plant Graveyard").performScrollTo().performClick()
        assert(called)
    }

    private fun tapVersionRow(times: Int) {
        repeat(times) {
            composeTestRule.onNodeWithTag("settings_about_version_row").performScrollTo().performClick()
            composeTestRule.waitForIdle()
        }
    }

    /**
     * Waits for the developer master switch to appear or disappear.
     *
     * Toggling developer mode writes to DataStore off the main thread and surfaces via a
     * `stateIn` flow, so `waitForIdle()` alone returns before the new value has propagated.
     */
    private fun waitForDeveloperSwitch(present: Boolean) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("developer_mode_switch")
                .fetchSemanticsNodes().isNotEmpty() == present
        }
    }

    @Test
    fun developerSection_isAbsent_byDefault() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        composeTestRule.onNodeWithTag("developer_mode_switch").assertDoesNotExist()
    }

    @Test
    fun developerSection_doesNotAppear_afterFourTaps() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        tapVersionRow(4)

        composeTestRule.onNodeWithTag("developer_mode_switch").assertDoesNotExist()
    }

    @Test
    fun developerSection_appears_afterFiveTaps() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        tapVersionRow(5)
        waitForDeveloperSwitch(present = true)

        composeTestRule.onNodeWithTag("developer_mode_switch").performScrollTo().assertIsOn()
    }

    @Test
    fun developerSection_hidden_afterMasterSwitchToggledOff() {
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        tapVersionRow(5)
        waitForDeveloperSwitch(present = true)
        composeTestRule.onNodeWithTag("developer_mode_switch").performScrollTo().performClick()
        waitForDeveloperSwitch(present = false)

        composeTestRule.onNodeWithTag("developer_mode_switch").assertDoesNotExist()
    }
}
