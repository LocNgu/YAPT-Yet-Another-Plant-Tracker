package com.yapt.planttracker.ui.screens.settings

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.R
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlag
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    private lateinit var context: Context
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var plantRepository: PlantRepository

    private val testFlag = FeatureFlag(
        key = "test_flag",
        titleRes = R.string.feature_flag_test_title,
        descriptionRes = R.string.feature_flag_test_description,
        default = false
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        dataStoreScope = CoroutineScope(Dispatchers.IO)
        dataStoreFile = File(context.cacheDir, "settings_test_${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { dataStoreFile }
        )

        plantRepository = PlantRepository(database.plantDao())
        viewModel = SettingsViewModel(dataStore, context, database, plantRepository)
    }

    private fun buildViewModelWithFlags(flags: List<FeatureFlag>) =
        SettingsViewModel(dataStore, context, database, plantRepository, featureFlags = FeatureFlags(dataStore, flags))

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

    /**
     * Taps the About version row [times] times via its click semantics action rather than an
     * injected touch.
     *
     * From tap 3 onward the gesture shows a countdown Snackbar, which the Scaffold renders at
     * the bottom of the screen - directly over the version row, since About sits near the end
     * of Settings. An injected touch would be consumed by the Snackbar, so taps 4 and 5 would
     * never reach the row. Invoking the semantics action bypasses hit-testing entirely.
     */
    private fun tapVersionRow(times: Int) {
        repeat(times) {
            composeTestRule.onNodeWithTag("settings_about_version_row")
                .performSemanticsAction(SemanticsActions.OnClick)
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

    /**
     * Waits for the injected test flag's switch to report [on], for the same reason as
     * [waitForDeveloperSwitch]: the switch reflects a DataStore write that round-trips through a
     * `stateIn` flow off the main thread, so `waitForIdle()` returns before it has propagated.
     */
    private fun waitForTestFlagSwitch(on: Boolean) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(
                hasTestTag("feature_flag_switch_test_flag") and if (on) isOn() else isOff()
            ).fetchSemanticsNodes().isNotEmpty()
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

        composeTestRule.onNodeWithTag("developer_mode_switch").assertIsOn()
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

        // The "Developer mode enabled" Snackbar is showing over the switch at this point,
        // so go through the semantics action here too rather than an injected touch.
        composeTestRule.onNodeWithTag("developer_mode_switch")
            .performSemanticsAction(SemanticsActions.OnClick)
        waitForDeveloperSwitch(present = false)

        composeTestRule.onNodeWithTag("developer_mode_switch").assertDoesNotExist()
    }

    @Test
    fun featureFlagsEmptyState_isDisplayed_whenRegistryIsEmpty() {
        // Inject an explicitly empty flag list rather than relying on FeatureFlagRegistry, which
        // ships real flags now (#436) — this case asserts empty-registry rendering and must keep
        // that meaning as flags come and go.
        val emptyFlagsViewModel = buildViewModelWithFlags(emptyList())
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = emptyFlagsViewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        tapVersionRow(5)
        waitForDeveloperSwitch(present = true)

        composeTestRule.onNodeWithText("No feature flags in this build").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun injectedFeatureFlag_rendersTitleDescriptionAndSwitch() {
        val flagViewModel = buildViewModelWithFlags(listOf(testFlag))
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = flagViewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        tapVersionRow(5)
        waitForDeveloperSwitch(present = true)

        composeTestRule.onNodeWithText("Test flag").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Used only by Compose tests to prove registry-driven rendering.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("feature_flag_switch_test_flag").assertIsOff()
    }

    @Test
    fun injectedFeatureFlag_togglingFlipsPersistedValue() {
        // Unlock developer mode by seeding the preference rather than tapping the version row
        // 5×. The unlock gesture is already covered by its own tests, and driving it here would
        // raise the "Developer mode enabled" Snackbar over the bottom of the Settings list —
        // exactly where the flag switch sits — letting it swallow the tap below.
        runBlocking { dataStore.edit { it[SettingsKeys.DEVELOPER_MODE_ENABLED] = true } }

        val flagViewModel = buildViewModelWithFlags(listOf(testFlag))
        composeTestRule.setContent {
            SettingsScreen(
                viewModel = flagViewModel,
                onNavigateBack = {},
                onRestoreSuccess = { _, _ -> },
                onShowWhatsNew = {}
            )
        }

        waitForDeveloperSwitch(present = true)

        composeTestRule.onNodeWithTag("feature_flag_switch_test_flag").performScrollTo().assertIsOff()
        composeTestRule.onNodeWithTag("feature_flag_switch_test_flag").performClick()

        // The Switch is a controlled component fed by a DataStore-backed `stateIn` flow, so the
        // write round-trips off the main thread; `waitForIdle()` returns before it lands.
        waitForTestFlagSwitch(on = true)

        // Read the underlying DataStore directly (rather than the ViewModel's StateFlow) to
        // prove the toggle actually persisted, not just updated in-memory Compose state.
        val persisted = runBlocking { dataStore.data.first()[FeatureFlags.preferenceKeyFor(testFlag)] }
        assertEquals(true, persisted)
    }

    @Test
    fun resetWhatsNewRow_isDisplayed_afterUnlock() {
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

        composeTestRule.onNodeWithText("Reset What's New seen state").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun resetWhatsNewRow_onClick_showsConfirmationSnackbar() {
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

        composeTestRule.onNodeWithTag("dev_mode_reset_whats_new_row").performScrollTo().performClick()

        composeTestRule.onNodeWithText("What's New seen state reset").assertIsDisplayed()
    }

    /**
     * Pins the "newer message replaces the current one" contract of the single snackbar stream.
     *
     * The 5 version-row taps leave "Developer mode enabled" showing; clicking a debug row
     * immediately after must swap that message out for the debug confirmation, not queue behind
     * it. A plain `collect` in the stream's collector parks inside `showSnackbar` until the
     * current snackbar times out, which regressed this into queueing and made the confirmation
     * appear seconds late (caught on CI, fixed by collecting with `collectLatest`).
     */
    @Test
    fun debugActionSnackbar_replacesTheUnlockSnackbar_ratherThanQueuingBehindIt() {
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
        composeTestRule.onNodeWithText("Developer mode enabled").assertIsDisplayed()

        composeTestRule.onNodeWithTag("dev_mode_reset_whats_new_row").performScrollTo().performClick()

        composeTestRule.onNodeWithText("What's New seen state reset").assertIsDisplayed()
        composeTestRule.onNodeWithText("Developer mode enabled").assertDoesNotExist()
    }

    @Test
    fun runReminderCheckRow_isDisplayed_afterUnlock() {
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

        composeTestRule.onNodeWithText("Run reminder check now").performScrollTo().assertIsDisplayed()
    }

    // The click-through outcome of "Run reminder check now" is deliberately not asserted here,
    // for two reasons. First, its two branches depend on the POST_NOTIFICATIONS grant state,
    // which this suite does not control, so the only assertion possible on-device was "one of
    // the two messages appeared" - which cannot tell a correct branch from an incorrect one.
    // Second, an earlier attempt at this test (`runReminderCheckRow_onClick_showsSnackbar`)
    // intermittently failed on CI with the snackbar node present but not displayed: multiple
    // uncoordinated coroutines (this row's debugActionEvent, the version-row tap countdown, the
    // backup-result collector) were each calling dismiss()+showSnackbar() on the same
    // SnackbarHostState, so a message could be shown and immediately superseded before the test
    // could observe it. That race is now fixed - every snackbar producer routes through a single
    // ordered stream with one collector (see SettingsScreen.kt) - but the underlying grant-state
    // non-determinism from the first reason still rules out a dual-outcome assertion here.
    // Both branches are pinned exactly in SettingsViewModelTest (granted -> ReminderScheduler.runNow
    // + confirmation; denied -> no enqueue + explanatory message), and the row-click ->
    // debugActionEvent -> snackbar wiring is covered deterministically by
    // resetWhatsNewRow_onClick_showsConfirmationSnackbar above.
}
