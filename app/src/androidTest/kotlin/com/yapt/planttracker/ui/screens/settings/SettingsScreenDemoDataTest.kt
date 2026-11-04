package com.yapt.planttracker.ui.screens.settings

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.R
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.devmode.DemoData
import com.yapt.planttracker.domain.model.Plant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Compose coverage for the Developer section's "Seed demo plants" / "Remove demo plants"
 * confirmation dialogs (#533, follow-up to #523): confirming runs the action against the real
 * database and shows the result snackbar; cancelling closes the dialog without writing.
 *
 * Split out of `SettingsScreenTest` to keep that file from growing further; the setup mirrors it
 * (real in-memory Room DB, real file-backed DataStore, real [SettingsViewModel]).
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenDemoDataTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: PlantDatabase
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var context: Context
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var plantRepository: PlantRepository
    private lateinit var viewModel: SettingsViewModel

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val REAL_PLANT = "My Real Fern"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataStoreScope = CoroutineScope(Dispatchers.IO)
        dataStoreFile = File(context.cacheDir, "settings_demo_test_${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { dataStoreFile })
        plantRepository = PlantRepository(database.plantDao())
        viewModel = SettingsViewModel(dataStore, context, database, plantRepository)
    }

    @After
    fun tearDown() {
        database.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
    }

    private fun addPlant(name: String) = runBlocking {
        plantRepository.addPlant(Plant(name = name, createdAt = 0L, updatedAt = 0L))
    }

    private fun plantNames(): List<String> =
        runBlocking { database.plantDao().getAllPlantsIncludingArchived().first().map { it.name } }

    /** Shows Settings and unlocks the Developer section (5 version-row taps, as in `SettingsScreenTest`). */
    private fun showSettingsWithDeveloperSection() {
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel, onRestoreSuccess = { _, _ -> }, onShowWhatsNew = {})
        }
        // Semantics actions rather than touches (here and on the Developer rows below): the unlock
        // snackbar sits at the bottom of the screen and would swallow an injected tap.
        repeat(5) {
            composeTestRule.onNodeWithTag("settings_about_version_row")
                .performSemanticsAction(SemanticsActions.OnClick)
            composeTestRule.waitForIdle()
        }
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("developer_mode_switch").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(TIMEOUT_MS) {
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun seedDemoDialog_confirm_seedsDemoPlantsAndShowsResultSnackbar() {
        showSettingsWithDeveloperSection()

        composeTestRule.onNodeWithTag("dev_mode_seed_demo_row").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText("Seed demo plants?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Seed").performClick()

        waitForText("10 demo plants seeded")
        composeTestRule.onNodeWithText("10 demo plants seeded").assertIsDisplayed()
        composeTestRule.onNodeWithText("Seed demo plants?").assertDoesNotExist()
        val names = plantNames()
        assertEquals(10, names.size)
        assertTrue(names.all { it.startsWith(DemoData.NAME_PREFIX) })
    }

    @Test
    fun seedDemoDialog_cancel_closesWithoutSeeding() {
        showSettingsWithDeveloperSection()

        composeTestRule.onNodeWithTag("dev_mode_seed_demo_row").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText("Seed demo plants?").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Seed demo plants?").assertDoesNotExist()
        composeTestRule.onNodeWithText("10 demo plants seeded").assertDoesNotExist()
        assertEquals(emptyList<String>(), plantNames())
    }

    @Test
    fun removeDemoDialog_confirm_removesOnlyDemoPlantsAndShowsResultSnackbar() {
        addPlant(REAL_PLANT)
        addPlant("${DemoData.NAME_PREFIX}Monstera")
        addPlant("${DemoData.NAME_PREFIX}Pothos")
        showSettingsWithDeveloperSection()

        composeTestRule.onNodeWithTag("dev_mode_remove_demo_row").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText("Remove demo plants?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Remove").performClick()

        waitForText("2 demo plants removed")
        composeTestRule.onNodeWithText("2 demo plants removed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Remove demo plants?").assertDoesNotExist()
        assertEquals(listOf(REAL_PLANT), plantNames())
    }

    @Test
    fun removeDemoDialog_cancel_closesWithoutRemoving() {
        addPlant("${DemoData.NAME_PREFIX}Monstera")
        showSettingsWithDeveloperSection()

        composeTestRule.onNodeWithTag("dev_mode_remove_demo_row").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText("Remove demo plants?").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Remove demo plants?").assertDoesNotExist()
        composeTestRule.onNodeWithText("1 demo plant removed").assertDoesNotExist()
        assertEquals(listOf("${DemoData.NAME_PREFIX}Monstera"), plantNames())
    }
}
