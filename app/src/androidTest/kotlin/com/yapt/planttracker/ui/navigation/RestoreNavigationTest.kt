package com.yapt.planttracker.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Settings is a root tab, so a restore can now finish with Settings on the back stack beneath the
// reset. showRestoredPlantList() must still leave exactly Care -> Plants (with the message).
@RunWith(AndroidJUnit4::class)
class RestoreNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var navController: NavHostController

    private fun setUpGraph() {
        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Screen.Today.route) {
                composable(Screen.Today.route) { Text("Care") }
                composable(
                    route = Screen.PlantList.route,
                    arguments = listOf(
                        navArgument("restoreMessage") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { Text("Plants") }
                composable(Screen.Settings.route) { Text("Settings") }
                composable(Screen.Graveyard.route) { Text("Graveyard") }
            }
        }
    }

    private fun onUi(block: () -> Unit) {
        composeTestRule.runOnUiThread(block)
        composeTestRule.waitForIdle()
    }

    private fun rootTabsOnBackStack(): List<String> = navController.currentBackStack.value
        .mapNotNull { it.destination.route }
        .filter { it in setOf(Screen.Today.route, Screen.PlantList.route, Screen.Settings.route) }

    @Test
    fun restoreFromSettingsLandsOnPlantsAboveCareWithTheMessage() {
        setUpGraph()
        onUi { navController.navigateToRootTab(Screen.Settings.route) }
        assertEquals(Screen.Settings.route, navController.currentBackStackEntry?.destination?.route)

        onUi { navController.showRestoredPlantList(plantCount = 3, logCount = 12) }

        assertEquals(Screen.PlantList.route, navController.currentBackStackEntry?.destination?.route)
        assertEquals(
            "Restored 3 plants and 12 logs",
            navController.currentBackStackEntry?.arguments?.getString("restoreMessage")
        )
        assertEquals(Screen.Today.route, navController.previousBackStackEntry?.destination?.route)
        assertEquals(listOf(Screen.Today.route, Screen.PlantList.route), rootTabsOnBackStack())
    }

    @Test
    fun backFromPlantsAfterRestoreGoesToCare() {
        setUpGraph()
        onUi { navController.navigateToRootTab(Screen.Settings.route) }
        onUi { navController.showRestoredPlantList(plantCount = 1, logCount = 0) }

        onUi { navController.popBackStack() }

        assertEquals(Screen.Today.route, navController.currentBackStackEntry?.destination?.route)
        assertNull(navController.currentBackStackEntry?.arguments?.getString("restoreMessage"))
    }
}
