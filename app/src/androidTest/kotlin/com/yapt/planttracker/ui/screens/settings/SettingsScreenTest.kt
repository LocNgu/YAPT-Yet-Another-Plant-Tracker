package com.yapt.planttracker.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.db.PlantDatabase
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

        viewModel = SettingsViewModel(dataStore, context, database)
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

        composeTestRule.onNodeWithText("Export backup").assertIsDisplayed()
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

        composeTestRule.onNodeWithText("Restore from backup").assertIsDisplayed()
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
}
