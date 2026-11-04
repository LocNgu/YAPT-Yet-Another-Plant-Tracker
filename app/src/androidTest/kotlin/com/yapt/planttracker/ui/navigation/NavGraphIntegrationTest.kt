package com.yapt.planttracker.ui.navigation

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.edit
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.BuildConfig
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Drives the real YaptNavGraph through an injected TestNavHostController (#472). Covers the gaps the
// other navigation tests leave: plant taps from Care and Plants, both deep links, and Back from a
// deep-linked Plant Detail. Back-stack checks never assume Plants is present (#836).
@RunWith(AndroidJUnit4::class)
class NavGraphIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<YaptApplication>()

    private lateinit var navController: TestNavHostController
    private var fernId: Long = 0L
    private var deepLinkConsumedCount = 0

    @Before
    fun setUp() = runBlocking(Dispatchers.IO) {
        application.database.clearAllTables()
        application.settingsDataStore.edit { preferences ->
            preferences.clear()
            preferences[SettingsKeys.LAST_SEEN_VERSION_CODE] = BuildConfig.VERSION_CODE
        }
        // Interval 1 with no logs: due today, so the plant gets a Care tile.
        fernId = application.plantRepository.addPlant(
            Plant(name = FERN, wateringIntervalDays = 1, pinIntervalToBase = true)
        )
    }

    @After
    fun tearDown() = runBlocking(Dispatchers.IO) {
        application.database.clearAllTables()
    }

    private fun setGraph(initialPlantId: Long? = null) {
        composeTestRule.setContent {
            navController = remember {
                TestNavHostController(application).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                    navigatorProvider.addNavigator(DialogNavigator())
                }
            }
            YaptNavGraph(
                app = application,
                initialPlantId = initialPlantId,
                onDeepLinkConsumed = { deepLinkConsumedCount++ },
                navController = navController
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun currentRoute(): String? = navController.currentBackStackEntry?.destination?.route

    private fun waitForRoute(route: String) {
        composeTestRule.waitUntil(TIMEOUT_MS) { currentRoute() == route }
    }

    private fun backStackRoutes(): List<String?> =
        navController.backStack.map { it.destination.route }.filter { it != null && it != "" }

    private fun assertOnPlantDetailFor(plantId: Long, plantName: String) {
        waitForRoute(Screen.PlantDetail.route)
        assertEquals(plantId, navController.currentBackStackEntry?.arguments?.getLong("plantId"))
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(plantName).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(plantName).onFirst().assertIsDisplayed()
    }

    private fun tab(label: String) = composeTestRule.onNode(hasText(label).and(hasClickAction()))

    @Test
    fun tappingAPlantOnACareTileOpensItsPlantDetail() {
        setGraph()
        waitForRoute(Screen.Today.route)
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasText(FERN).and(hasClickAction())).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onAllNodes(hasText(FERN).and(hasClickAction())).onFirst().performClick()

        assertOnPlantDetailFor(fernId, FERN)
        assertEquals(listOf(Screen.Today.route, Screen.PlantDetail.route), backStackRoutes())
    }

    @Test
    fun tappingAPlantRowOnPlantsOpensItsPlantDetail() {
        setGraph()
        tab(PLANTS).performClick()
        waitForRoute(Screen.PlantList.route)
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasText(FERN).and(hasClickAction())).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onAllNodes(hasText(FERN).and(hasClickAction())).onFirst().performClick()

        assertOnPlantDetailFor(fernId, FERN)
        assertEquals(
            listOf(Screen.Today.route, Screen.PlantList.route, Screen.PlantDetail.route),
            backStackRoutes()
        )
    }

    @Test
    fun plantDeepLinkOpensPlantDetailAndIsConsumedOnce() {
        setGraph(initialPlantId = fernId)

        assertOnPlantDetailFor(fernId, FERN)
        assertEquals(1, deepLinkConsumedCount)

        // Recomposing the graph (tab switches change its bottom-bar state) must not re-consume the link.
        composeTestRule.onNodeWithContentDescription(BACK).performClick()
        waitForRoute(Screen.Today.route)
        tab(PLANTS).performClick()
        waitForRoute(Screen.PlantList.route)
        tab(CARE).performClick()
        waitForRoute(Screen.Today.route)
        assertEquals(1, deepLinkConsumedCount)
    }

    @Test
    fun caredTodayDeepLinkLandsOnPlantsAboveCareWithTheFilterApplied() {
        setGraph(initialPlantId = Screen.PlantList.CARED_TODAY_DEEP_LINK_ID)

        waitForRoute(Screen.PlantList.route)
        assertEquals(1, deepLinkConsumedCount)
        assertEquals(listOf(Screen.Today.route, Screen.PlantList.route), backStackRoutes())
        tab(PLANTS).assertIsSelected()
        // Nothing has been cared for, so the Cared-for-today sort shows its own empty state.
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(EMPTY_CARED_TODAY).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(EMPTY_CARED_TODAY).assertIsDisplayed()

        Espresso.pressBack()

        waitForRoute(Screen.Today.route)
        tab(CARE).assertIsSelected()
    }

    @Test
    fun systemBackFromADeepLinkedPlantDetailReturnsToCare() {
        setGraph(initialPlantId = fernId)
        assertOnPlantDetailFor(fernId, FERN)

        // Espresso throws NoActivityResumedException if Back finished the activity instead.
        Espresso.pressBack()

        waitForRoute(Screen.Today.route)
        assertEquals(listOf(Screen.Today.route), backStackRoutes())
        tab(CARE).assertIsSelected()
    }

    @Test
    fun noDeepLinkStaysOnCareWithoutConsuming() {
        setGraph(initialPlantId = null)

        waitForRoute(Screen.Today.route)
        tab(CARE).assertIsSelected()
        assertEquals(0, deepLinkConsumedCount)
        assertEquals(listOf(Screen.Today.route), backStackRoutes())
    }

    @Test
    fun deepLinkToAMissingPlantOpensPlantDetailWithoutCrashing() {
        // Records current behaviour: the route opens with the stale id and the screen stays usable.
        setGraph(initialPlantId = MISSING_ID)

        waitForRoute(Screen.PlantDetail.route)
        assertEquals(MISSING_ID, navController.currentBackStackEntry?.arguments?.getLong("plantId"))
        assertEquals(1, deepLinkConsumedCount)
        composeTestRule.onNodeWithContentDescription(BACK).performClick()
        waitForRoute(Screen.Today.route)
    }

    private companion object {
        const val FERN = "Fern"
        const val CARE = "Care"
        const val PLANTS = "Plants"
        const val BACK = "Back"
        const val EMPTY_CARED_TODAY = "No plants cared for yet today"
        const val MISSING_ID = 9_999L
        const val TIMEOUT_MS = 5_000L
    }
}
