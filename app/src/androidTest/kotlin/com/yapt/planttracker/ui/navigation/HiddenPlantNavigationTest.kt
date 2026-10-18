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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Hiding leaves Edit Plant like archiving does, and Care is the start destination, so Plants is not
// guaranteed to be on the back stack (#836). Runs showHiddenPlantOnPlantList() with Plants absent and present.
@RunWith(AndroidJUnit4::class)
class HiddenPlantNavigationTest {

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
                composable(Screen.PlantDetail.route) { Text("Detail") }
                composable(Screen.EditPlant.route) { Text("Edit") }
            }
        }
    }

    private fun navigateThroughRoutes(vararg routes: String) {
        composeTestRule.runOnUiThread { routes.forEach { navController.navigate(it) } }
        composeTestRule.waitForIdle()
    }

    private fun hide() {
        composeTestRule.runOnUiThread {
            val editEntry = navController.currentBackStackEntry!!
            navController.showHiddenPlantOnPlantList(editEntry, HIDDEN_ID, HIDDEN_NAME)
        }
        composeTestRule.waitForIdle()
    }

    private fun assertPlantListCarriesHiddenPlantAboveCare() {
        assertEquals(Screen.PlantList.route, navController.currentBackStackEntry?.destination?.route)
        assertEquals(Screen.Today.route, navController.previousBackStackEntry?.destination?.route)
        val handle = navController.getBackStackEntry(Screen.PlantList.route).savedStateHandle
        assertEquals(HIDDEN_ID, handle.get<Long>(HIDDEN_PLANT_ID_KEY))
        assertEquals(HIDDEN_NAME, handle.get<String>(HIDDEN_PLANT_NAME_KEY))
    }

    @Test
    fun hidingWithPlantListNotOnTheBackStackStacksItAboveCare() {
        setUpGraph()
        navigateThroughRoutes(Screen.PlantDetail.createRoute(1L), Screen.EditPlant.createRoute(1L))

        hide()

        assertPlantListCarriesHiddenPlantAboveCare()
    }

    @Test
    fun hidingWithPlantListOnTheBackStackPopsBackToIt() {
        setUpGraph()
        navigateThroughRoutes(
            Screen.PlantList.createRoute(),
            Screen.PlantDetail.createRoute(1L),
            Screen.EditPlant.createRoute(1L)
        )

        hide()

        assertPlantListCarriesHiddenPlantAboveCare()
    }

    private companion object {
        const val HIDDEN_ID = 7L
        const val HIDDEN_NAME = "Fern"
    }
}
