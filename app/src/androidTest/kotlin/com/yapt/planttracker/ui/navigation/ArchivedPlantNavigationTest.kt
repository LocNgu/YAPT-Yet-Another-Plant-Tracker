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

// Care is the start destination, so Plants is not guaranteed to be on the back stack when Edit Plant
// archives a plant (Care -> Plant Detail -> Edit). These run showArchivedPlantOnPlantList() against a
// real NavHost with the app's route templates, once with Plants absent and once present.
@RunWith(AndroidJUnit4::class)
class ArchivedPlantNavigationTest {

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

    private fun archive() {
        composeTestRule.runOnUiThread {
            val editEntry = navController.currentBackStackEntry!!
            navController.showArchivedPlantOnPlantList(editEntry, ARCHIVED_ID, ARCHIVED_NAME)
        }
        composeTestRule.waitForIdle()
    }

    private fun assertPlantListShowsArchivedPlantAboveCare() {
        assertEquals(Screen.PlantList.route, navController.currentBackStackEntry?.destination?.route)
        assertEquals(Screen.Today.route, navController.previousBackStackEntry?.destination?.route)
        val handle = navController.getBackStackEntry(Screen.PlantList.route).savedStateHandle
        assertEquals(ARCHIVED_ID, handle.get<Long>("archivedPlantId"))
        assertEquals(ARCHIVED_NAME, handle.get<String>("archivedPlantName"))
    }

    @Test
    fun archivingWithPlantListNotOnTheBackStackStacksItAboveCare() {
        setUpGraph()
        navigateThroughRoutes(Screen.PlantDetail.createRoute(1L), Screen.EditPlant.createRoute(1L))

        archive()

        assertPlantListShowsArchivedPlantAboveCare()
    }

    @Test
    fun archivingWithPlantListOnTheBackStackPopsBackToIt() {
        setUpGraph()
        navigateThroughRoutes(
            Screen.PlantList.createRoute(),
            Screen.PlantDetail.createRoute(1L),
            Screen.EditPlant.createRoute(1L)
        )

        archive()

        assertPlantListShowsArchivedPlantAboveCare()
    }

    private companion object {
        const val ARCHIVED_ID = 7L
        const val ARCHIVED_NAME = "Fern"
    }
}
